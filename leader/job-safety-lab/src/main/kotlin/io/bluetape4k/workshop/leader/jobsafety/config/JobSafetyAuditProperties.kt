package io.bluetape4k.workshop.leader.jobsafety.config

import io.bluetape4k.support.requireNotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration
import java.util.Locale

private const val AUDIT_DEFAULT_QUEUE_CAPACITY: Int = 32
private const val AUDIT_DEFAULT_MAX_IN_FLIGHT: Int = 4
private const val AUDIT_DEFAULT_MAX_ATTEMPTS: Int = 3
private const val AUDIT_DEFAULT_MAX_PAYLOAD_BYTES: Int = 64 * 1024
private const val AUDIT_DEFAULT_RECENT_HISTORY_LIMIT: Int = 32
private const val AUDIT_DEFAULT_RECENT_HISTORY_BYTE_BUDGET: Long = 512L * 1024
private const val AUDIT_DEFAULT_MAX_BUFFERED_BYTES: Long = 72L * 1024 * 1024

/**
 * job-safety lifecycle audit export의 transport와 메모리 경계를 정의한다.
 *
 * 기본 transport는 외부 DNS/socket/credential을 사용하지 않는 [AuditTransport.MEMORY]다.
 * [AuditTransport.HTTPS]는 명시적인 endpoint와 exact host allow-list가 모두 필요하며,
 * endpoint와 authorization은 이 properties 객체의 문자열 표현이나 audit report에 포함하지 않는다.
 *
 * upstream pending context가 보유하는 raw `lockName`/`nodeId`/`slotId`는 workshop이
 * 주입할 수 없는 upstream 내부 값이다. upstream의 4,096 entry/15분 TTL 경계는 적용되지만
 * 해당 문자열의 실제 JVM heap byte bound는 이 예제의 aggregate budget에 포함하지 않는다.
 */
@ConfigurationProperties("workshop.job-safety.audit")
data class JobSafetyAuditProperties(
    val transport: AuditTransport = AuditTransport.MEMORY,
    val endpoint: String? = null,
    val allowedHosts: Set<String> = emptySet(),
    val headers: AuditHeaders = AuditHeaders(),
    val queueCapacity: Int = AUDIT_DEFAULT_QUEUE_CAPACITY,
    val maxInFlight: Int = AUDIT_DEFAULT_MAX_IN_FLIGHT,
    val maxAttempts: Int = AUDIT_DEFAULT_MAX_ATTEMPTS,
    val attemptTimeout: Duration = Duration.ofSeconds(2),
    val initialBackoff: Duration = Duration.ofMillis(100),
    val maxBackoff: Duration = Duration.ofSeconds(1),
    val maxPayloadBytes: Int = AUDIT_DEFAULT_MAX_PAYLOAD_BYTES,
    val recentHistoryLimit: Int = AUDIT_DEFAULT_RECENT_HISTORY_LIMIT,
    val recentHistoryByteBudget: Long = AUDIT_DEFAULT_RECENT_HISTORY_BYTE_BUDGET,
    val maxBufferedBytes: Long = AUDIT_DEFAULT_MAX_BUFFERED_BYTES,
    val shutdownTimeout: Duration = Duration.ofSeconds(2),
) {
    /** endpoint host를 DNS canonical form으로 반환한다. MEMORY transport에서는 null이다. */
    val endpointHost: String? = endpoint?.let {
        parseEndpointUri(it).host?.canonicalAuditHost("endpoint host")
    }

    /** allow-list를 lower-case 및 trailing-dot 제거 형태로 반환한다. */
    val canonicalAllowedHosts: Set<String> = allowedHosts.map {
        it.canonicalAuditHost("allowedHosts entry")
    }.toSet()

    init {
        validateTransportConfiguration()
        validateExportBounds()

        val queueAndFlight = try {
            Math.addExact(queueCapacity.toLong(), maxInFlight.toLong())
        } catch (overflow: ArithmeticException) {
            throw IllegalArgumentException("audit queue/in-flight reservation overflows", overflow)
        }
        val reservation = checkedAuditReservation(
            queueAndFlight = queueAndFlight,
            maxPayloadBytes = maxPayloadBytes.toLong(),
            recentHistoryByteBudget = recentHistoryByteBudget,
        )
        require(reservation <= maxBufferedBytes) {
            "audit retained-byte reservation must be <= maxBufferedBytes: $reservation > $maxBufferedBytes"
        }
    }

    private fun validateTransportConfiguration() {
        when (transport) {
            AuditTransport.MEMORY -> {
                require(endpoint == null) {
                    "endpoint is only allowed when transport is HTTPS"
                }
                require(headers.isEmpty()) {
                    "authorization headers are only allowed when transport is HTTPS"
                }
                require(allowedHosts.isEmpty()) {
                    "allowedHosts is only allowed when transport is HTTPS"
                }
            }

            AuditTransport.HTTPS -> {
                val configuredEndpoint = endpoint.requireNotBlank("endpoint")
                val uri = parseEndpointUri(configuredEndpoint)
                validateHttpsUri(uri)
                require(canonicalAllowedHosts.isNotEmpty()) {
                    "allowedHosts must not be empty when transport is HTTPS"
                }
                val host = uri.host ?: throw IllegalArgumentException("endpoint host must not be blank")
                val canonicalHost = host.canonicalAuditHost("endpoint host")
                require(canonicalHost in canonicalAllowedHosts) {
                    "endpoint host must be present in allowedHosts"
                }
            }
        }
    }

    private fun parseEndpointUri(value: String): URI = try {
        URI(value)
    } catch (error: Exception) {
        throw IllegalArgumentException("endpoint must be a valid URI", error)
    }

    override fun toString(): String =
        "JobSafetyAuditProperties(transport=$transport, queueCapacity=$queueCapacity, " +
            "maxInFlight=$maxInFlight, maxAttempts=$maxAttempts, attemptTimeout=$attemptTimeout, " +
            "initialBackoff=$initialBackoff, maxBackoff=$maxBackoff, maxPayloadBytes=$maxPayloadBytes, " +
            "recentHistoryLimit=$recentHistoryLimit, recentHistoryByteBudget=$recentHistoryByteBudget, " +
            "maxBufferedBytes=$maxBufferedBytes, shutdownTimeout=$shutdownTimeout)"

    private fun validateExportBounds() {
        require(queueCapacity in 1..MAX_QUEUE_CAPACITY) {
            "queueCapacity must be in 1..$MAX_QUEUE_CAPACITY: $queueCapacity"
        }
        require(maxInFlight in 1..queueCapacity) {
            "maxInFlight must be in 1..queueCapacity: $maxInFlight"
        }
        require(maxAttempts in 1..MAX_ATTEMPTS) {
            "maxAttempts must be in 1..$MAX_ATTEMPTS: $maxAttempts"
        }
        attemptTimeout.requirePositiveAtMost("attemptTimeout", MAX_ATTEMPT_TIMEOUT)
        initialBackoff.requirePositiveAtMost("initialBackoff", MAX_BACKOFF)
        maxBackoff.requirePositiveAtMost("maxBackoff", MAX_BACKOFF)
        require(initialBackoff <= maxBackoff) {
            "initialBackoff must be <= maxBackoff"
        }
        require(maxPayloadBytes in 1..MAX_PAYLOAD_BYTES) {
            "maxPayloadBytes must be in 1..$MAX_PAYLOAD_BYTES: $maxPayloadBytes"
        }
        require(recentHistoryLimit in 1..MAX_RECENT_HISTORY_LIMIT) {
            "recentHistoryLimit must be in 1..$MAX_RECENT_HISTORY_LIMIT: $recentHistoryLimit"
        }
        require(recentHistoryByteBudget in 1..MAX_RECENT_HISTORY_BYTE_BUDGET) {
            "recentHistoryByteBudget must be in 1..$MAX_RECENT_HISTORY_BYTE_BUDGET: $recentHistoryByteBudget"
        }
        require(maxBufferedBytes in 1..MAX_BUFFERED_BYTES) {
            "maxBufferedBytes must be in 1..$MAX_BUFFERED_BYTES: $maxBufferedBytes"
        }
        shutdownTimeout.requirePositiveAtMost("shutdownTimeout", MAX_SHUTDOWN_TIMEOUT)
    }

    companion object {
        const val DEFAULT_QUEUE_CAPACITY: Int = AUDIT_DEFAULT_QUEUE_CAPACITY
        const val DEFAULT_MAX_IN_FLIGHT: Int = AUDIT_DEFAULT_MAX_IN_FLIGHT
        const val DEFAULT_MAX_ATTEMPTS: Int = AUDIT_DEFAULT_MAX_ATTEMPTS
        const val DEFAULT_MAX_PAYLOAD_BYTES: Int = AUDIT_DEFAULT_MAX_PAYLOAD_BYTES
        const val DEFAULT_RECENT_HISTORY_LIMIT: Int = AUDIT_DEFAULT_RECENT_HISTORY_LIMIT
        const val DEFAULT_MAX_RECENT_HISTORY_BYTE_BUDGET: Long = AUDIT_DEFAULT_RECENT_HISTORY_BYTE_BUDGET
        const val DEFAULT_MAX_BUFFERED_BYTES: Long = AUDIT_DEFAULT_MAX_BUFFERED_BYTES

        const val MAX_QUEUE_CAPACITY: Int = 65_536
        const val MAX_ATTEMPTS: Int = 16
        const val MAX_PAYLOAD_BYTES: Int = 1024 * 1024
        const val MAX_RECENT_HISTORY_LIMIT: Int = 4_096
        const val MAX_RECENT_HISTORY_BYTE_BUDGET: Long = 1024L * 1024
        const val MAX_BUFFERED_BYTES: Long = 128L * 1024 * 1024
        val MAX_ATTEMPT_TIMEOUT: Duration = Duration.ofMinutes(5)
        val MAX_BACKOFF: Duration = Duration.ofMinutes(1)
        val MAX_SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(30)

        /** aggregate audit reservation을 checked `Long` 산술로 계산한다. */
        @JvmStatic
        internal fun checkedAuditReservation(
            queueAndFlight: Long,
            maxPayloadBytes: Long,
            recentHistoryByteBudget: Long,
            pendingMetadataBytes: Long = DEFAULT_PENDING_METADATA_BYTES,
        ): Long = try {
            require(queueAndFlight >= 0) { "queueAndFlight must not be negative" }
            require(maxPayloadBytes >= 0) { "maxPayloadBytes must not be negative" }
            require(recentHistoryByteBudget >= 0) { "recentHistoryByteBudget must not be negative" }
            require(pendingMetadataBytes >= 0) { "pendingMetadataBytes must not be negative" }

            val copiedPayload = Math.multiplyExact(Math.multiplyExact(queueAndFlight, maxPayloadBytes), 2L)
            Math.addExact(Math.addExact(copiedPayload, recentHistoryByteBudget), pendingMetadataBytes)
        } catch (overflow: ArithmeticException) {
            throw IllegalArgumentException("audit retained-byte reservation overflows", overflow)
        }
    }
}

/** audit 전송 경계의 transport 종류다. */
enum class AuditTransport {
    MEMORY,
    HTTPS,
}

/**
 * HTTP audit 헤더 중 허용된 authorization만 보관하는 value type이다.
 *
 * 보안상 `toString`, `equals`, `hashCode`는 secret 원문을 사용하지 않는다. 서로 다른
 * authorization secret은 모두 같은 "헤더가 존재함" 상태로 비교한다.
 */
class AuditHeaders(private val authorization: String? = null) {
    init {
        authorization?.let { value ->
            value.requireNotBlank("authorization")
            require(!value.containsHttpControlCharacter()) {
                "authorization must not contain control characters"
            }
            require(value.toByteArray(Charsets.UTF_8).size <= MAX_AUTHORIZATION_BYTES) {
                "authorization must be at most $MAX_AUTHORIZATION_BYTES UTF-8 bytes"
            }
        }
    }

    /** upstream HTTP allow-list에 전달할 immutable header map이다. */
    fun asMap(): Map<String, String> = authorization?.let {
        mapOf("Authorization" to it)
    }.orEmpty()

    /** authorization이 설정되었는지만 반환한다. */
    fun isEmpty(): Boolean = authorization == null

    override fun toString(): String = "AuditHeaders(authorization=<redacted>)"

    override fun equals(other: Any?): Boolean =
        other is AuditHeaders && (authorization != null) == (other.authorization != null)

    override fun hashCode(): Int = if (authorization == null) 0 else 1

    private companion object {
        const val MAX_AUTHORIZATION_BYTES: Int = 8 * 1024
    }
}

/**
 * audit retained-byte aggregate를 checked `Long` 산술로 계산한다.
 *
 * 이 함수는 같은 package의 경계 테스트와 properties validation이 공유한다.
 */
internal fun checkedAuditReservation(
    queueAndFlight: Long,
    maxPayloadBytes: Long,
    recentHistoryByteBudget: Long,
    pendingMetadataBytes: Long = DEFAULT_PENDING_METADATA_BYTES,
): Long = JobSafetyAuditProperties.checkedAuditReservation(
    queueAndFlight = queueAndFlight,
    maxPayloadBytes = maxPayloadBytes,
    recentHistoryByteBudget = recentHistoryByteBudget,
    pendingMetadataBytes = pendingMetadataBytes,
)

private const val DEFAULT_PENDING_METADATA_BYTES: Long = 4_096L * 16L * 1024L
private const val MAX_AUTHORIZATION_BYTES: Int = 8 * 1024
private const val HTTP_CONTROL_CHARACTER_MIN_CODE: Int = 0x20
private const val HTTP_DELETE_CHARACTER_CODE: Int = 0x7f
private val HOST_LABEL_PATTERN = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
private val IPV4_LITERAL_PATTERN = Regex("[0-9]+(?:\\.[0-9]+){3}")
private val ALTERNATE_IPV4_LITERAL_PATTERN =
    Regex("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+)){0,3}")

private fun Duration.requirePositiveAtMost(name: String, maximum: Duration) {
    require(!isZero && !isNegative) { "$name must be positive: $this" }
    try {
        require(toNanos() > 0) { "$name must be positive: $this" }
    } catch (overflow: ArithmeticException) {
        throw IllegalArgumentException("$name does not fit in nanoseconds: $this", overflow)
    }
    require(this <= maximum) { "$name must be <= $maximum: $this" }
}

private fun validateHttpsUri(uri: URI) {
    require(uri.isAbsolute && !uri.isOpaque) {
        "endpoint must be an absolute hierarchical URI"
    }
    require(uri.scheme?.equals("https", ignoreCase = true) == true) {
        "endpoint scheme must be https"
    }
    require(uri.userInfo == null) { "endpoint must not contain user-info" }
    require(uri.query == null) { "endpoint must not contain a query" }
    require(uri.fragment == null) { "endpoint must not contain a fragment" }
    require(!uri.toString().containsHttpControlCharacter()) {
        "endpoint must not contain control characters"
    }
    val host = uri.host
    require(!host.isNullOrBlank()) { "endpoint host must not be blank" }
    host.canonicalAuditHost("endpoint host")
}

private fun String.canonicalAuditHost(fieldName: String): String {
    val normalized = trim().lowercase(Locale.ROOT).removeSuffix(".")
    normalized.requireNotBlank(fieldName)
    require(!normalized.containsHttpControlCharacter()) {
        "$fieldName must not contain control characters"
    }
    require(normalized != "localhost" && !normalized.endsWith(".localhost")) {
        "$fieldName must not be localhost"
    }
    require(!normalized.contains(':') && !normalized.startsWith('[') && !normalized.endsWith(']')) {
        "$fieldName must not be an IPv6 literal"
    }
    require(!IPV4_LITERAL_PATTERN.matches(normalized)) {
        "$fieldName must not be an IPv4 literal"
    }
    require(!ALTERNATE_IPV4_LITERAL_PATTERN.matches(normalized)) {
        "$fieldName must not use an alternate IPv4 literal"
    }
    require(normalized.length <= 253) { "$fieldName must be at most 253 characters" }
    require(normalized.split('.').all { HOST_LABEL_PATTERN.matches(it) }) {
        "$fieldName must be a DNS hostname"
    }
    return normalized
}

private fun String.containsHttpControlCharacter(): Boolean = any {
    it.code < HTTP_CONTROL_CHARACTER_MIN_CODE || it.code == HTTP_DELETE_CHARACTER_CODE
}
