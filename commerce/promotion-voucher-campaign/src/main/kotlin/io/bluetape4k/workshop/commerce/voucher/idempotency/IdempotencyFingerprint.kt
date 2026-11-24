package io.bluetape4k.workshop.commerce.voucher.idempotency

import io.bluetape4k.io.digest.Base64Format
import io.bluetape4k.io.digest.Digesters
import io.bluetape4k.jackson3.jsonMapper
import io.bluetape4k.support.toUtf8Bytes
import tools.jackson.databind.JsonNode
import java.io.Serializable
import java.math.BigDecimal
import java.security.SecureRandom
import java.util.*

/** padding 없는 canonical Base64URL로 인코딩한 immutable SHA-256 또는 HMAC 크기 값입니다. */
/** padding 없는 canonical Base64URL로 인코딩한 immutable SHA-256 또는 HMAC 크기 값입니다. */
@JvmInline
internal value class Digest private constructor(
    /**
     * `base64Url`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val base64Url: String,
) {
    init {
        require(base64Url.length == SHA256_BASE64URL_LENGTH && BASE64URL.matches(base64Url)) {
            "digest must be a canonical 256-bit Base64URL value"
        }
    }

    companion object {
        /**
         * of은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param bytes 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun of(bytes: ByteArray): Digest = Digest(encodeBase64Url(bytes.copyOf()))

        /**
         * sha256은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun sha256(value: String): Digest =
            Digest(Digesters.SHA256.digestBase64Utf8(value, Base64Format.URL_SAFE_UNPADDED))
    }
}

/** secret acquisition capability입니다. persistence는 이 raw token이 아니라 [digest]만 받습니다. */
/** secret acquisition capability입니다. persistence는 이 raw token이 아니라 [digest]만 받습니다. */
@JvmInline
internal value class OwnerToken private constructor(
    /**
     * `base64Url`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val base64Url: String,
) {
    init {
        require(base64Url.length == SHA256_BASE64URL_LENGTH && BASE64URL.matches(base64Url)) {
            "owner token must be a canonical 256-bit Base64URL value"
        }
    }

    /**
     * digest은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun digest(): Digest = Digest.sha256("$OWNER_TOKEN_DOMAIN\u0000$base64Url")

    companion object {

        /**
         * invoke은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param bytes 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        operator fun invoke(bytes: ByteArray): OwnerToken =
            OwnerToken(encodeBase64Url(bytes.copyOf()))

        /**
         * of은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param bytes 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun of(bytes: ByteArray): OwnerToken = invoke(bytes)

        /**
         * random은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param random 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun random(random: SecureRandom = SECURE_RANDOM): OwnerToken =
            of(ByteArray(SHA256_BYTES).also(random::nextBytes))

        /**
         * `SECURE_RANDOM`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val SECURE_RANDOM = SecureRandom()
    }
}

/** bounded voucher command DTO를 canonicalize하는 데 사용하는 닫힌 scalar schema입니다. */
/** bounded voucher command DTO를 canonicalize하는 데 사용하는 닫힌 scalar schema입니다. */
internal sealed interface CanonicalField: Serializable {
    /**
     * `nullable`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val nullable: kotlin.Boolean
    /**
     * `nullEquivalentToOmitted`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val nullEquivalentToOmitted: kotlin.Boolean

    /**
     * canonical은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param node 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun canonical(node: JsonNode): String

    /**
     * Text은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class Text(
        /**
         * `nullable`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        override val nullable: kotlin.Boolean = false,
        /**
         * `nullEquivalentToOmitted`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        override val nullEquivalentToOmitted: kotlin.Boolean = false,
        /**
         * `default`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val default: String? = null,
    ): CanonicalField {
        /**
         * canonical은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param node 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        override fun canonical(node: JsonNode): String {
            require(node.isString) { "expected a JSON string" }
            return jsonString(node.stringValue())
        }
    }

    /**
     * Boolean은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class Boolean(
        /**
         * `nullable`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        override val nullable: kotlin.Boolean = false,
        /**
         * `nullEquivalentToOmitted`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        override val nullEquivalentToOmitted: kotlin.Boolean = false,
        /**
         * `default`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val default: kotlin.Boolean? = null,
    ): CanonicalField {
        /**
         * canonical은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param node 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        override fun canonical(node: JsonNode): String {
            require(node.isBoolean) { "expected a JSON boolean" }
            return node.booleanValue().toString()
        }
    }

    /**
     * Decimal은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    data class Decimal(
        /**
         * `nullable`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        override val nullable: kotlin.Boolean = false,
        /**
         * `nullEquivalentToOmitted`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        override val nullEquivalentToOmitted: kotlin.Boolean = false,
        /**
         * `default`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val default: BigDecimal? = null,
    ): CanonicalField {
        /**
         * canonical은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param node 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        override fun canonical(node: JsonNode): String {
            require(node.isNumber) { "expected a JSON number" }
            return canonicalDecimal(node.decimalValue())
        }
    }
}

/**
 * ClosedRequestSchema은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class ClosedRequestSchema(
    /**
     * `fields`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val fields: Map<String, CanonicalField>,
) {
    init {
        require(fields.isNotEmpty()) { "closed request schema must declare fields" }
        require(fields.keys.all { it.isNotBlank() }) { "schema field names must not be blank" }
    }
}

/** request body나 raw key를 보존하지 않고 domain-separated request fingerprint를 생성합니다. */
/** request body나 raw key를 보존하지 않고 domain-separated request fingerprint를 생성합니다. */
internal object IdempotencyFingerprint {
    /**
     * `mapper`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val mapper = jsonMapper { }

    /**
     * key은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun key(
        tenantId: String,
        principalDigest: Digest,
        operation: String,
        resourceId: String,
        rawKey: String,
    ): Digest {
        require(tenantId.isNotBlank()) { "tenantId must not be blank" }
        require(operation.isNotBlank()) { "operation must not be blank" }
        require(resourceId.isNotBlank()) { "resourceId must not be blank" }
        require(rawKey.length in MIN_KEY_LENGTH..MAX_KEY_LENGTH) {
            "Idempotency-Key must contain $MIN_KEY_LENGTH..$MAX_KEY_LENGTH characters"
        }
        return Digest.sha256(
            listOf(
                KEY_DOMAIN,
                tenantId,
                principalDigest.base64Url,
                operation,
                resourceId,
                rawKey
            ).joinToString("\u0000"),
        )
    }

    /**
     * request은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun request(
        method: String,
        path: String,
        resourceId: String,
        headers: Map<String, String>,
        body: String,
        schema: ClosedRequestSchema,
    ): Digest {
        require(method.isNotBlank()) { "HTTP method must not be blank" }
        require(resourceId.isNotBlank()) { "resourceId must not be blank" }
        require(body.toUtf8Bytes().size <= MAX_BODY_BYTES) { "request body is too large" }

        val canonical = buildString {
            append(REQUEST_DOMAIN).append('\n')
            append(method.trim().uppercase(Locale.ROOT)).append('\n')
            append(normalizePath(path)).append('\n')
            append(resourceId.trim()).append('\n')
            append(canonicalHeaders(headers)).append('\n')
            append(canonicalBody(body, schema))
        }
        return Digest.sha256(canonical)
    }

    /**
     * canonicalHeaders은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param headers 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun canonicalHeaders(headers: Map<String, String>): String {
        val normalized: Map<String, String> = headers.entries
            .map { it.key.trim().lowercase(Locale.ROOT) to it.value.trim() }
            .filter { it.first in SEMANTIC_HEADERS }
            .groupBy({ it.first }, { it.second })
            .mapValues { (name, values) ->
                require(values.distinct().size == 1) { "conflicting semantic header: $name" }
                require(values.single().none { it == '\r' || it == '\n' }) {
                    "semantic header must not contain a line break: $name"
                }
                values.single()
            }
        return SEMANTIC_HEADERS.sorted().joinToString("\n") { name ->
            "$name=${normalized[name].orEmpty()}"
        }
    }

    /**
     * canonicalBody은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun canonicalBody(
        body: String,
        schema: ClosedRequestSchema,
    ): String {
        val root = try {
            mapper.readTree(body)
        } catch (e: Exception) {
            throw IllegalArgumentException("request body must be valid JSON", e)
        }
        require(root.isObject) { "request body must be a JSON object" }

        val providedNames = root.propertyNames().toSet()
        val unknown = providedNames - schema.fields.keys
        require(unknown.isEmpty()) { "unknown request properties: ${unknown.sorted().joinToString()}" }

        return schema.fields.keys
            .sortedWith(UTF8_COMPARATOR)
            .mapNotNull { name ->
                val field = schema.fields.getValue(name)
                val node = root.get(name)
                when {
                    node == null                                 -> omittedEntry(name, field)
                    node.isNull && field.nullEquivalentToOmitted -> omittedEntry(name, field)
                    node.isNull && field.nullable                -> "$name=null"
                    node.isNull                                  -> throw IllegalArgumentException("$name must not be null")
                    else                                         -> "$name=${field.canonical(node)}"
                }
            }
            .joinToString("\n")
    }

    /**
     * omittedEntry은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun omittedEntry(
        name: String,
        field: CanonicalField,
    ): String? {
        val default = when (field) {
            is CanonicalField.Text    -> field.default?.let { "$name=${jsonString(it)}" }
            is CanonicalField.Boolean -> field.default?.let { "$name=$it" }
            is CanonicalField.Decimal -> field.default?.let { "$name=${canonicalDecimal(it)}" }
        }
        if (default != null || field.nullEquivalentToOmitted) return default
        throw IllegalArgumentException("required request property is missing: $name")
    }

    /**
     * normalizePath은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param path 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun normalizePath(path: String): String {
        require(path.startsWith('/')) { "path must be absolute" }
        val normalized = path.trim().replace(Regex("/{2,}"), "/").removeSuffix("/")
        return normalized.ifEmpty { "/" }
    }

    /**
     * `UTF8_COMPARATOR`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val UTF8_COMPARATOR = Comparator<String> { left, right ->
        compareUnsigned(left.toUtf8Bytes(), right.toUtf8Bytes())
    }

    /**
     * compareUnsigned은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun compareUnsigned(
        left: ByteArray,
        right: ByteArray,
    ): Int {
        val common = minOf(left.size, right.size)
        for (index in 0 until common) {
            val compared = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (compared != 0) return compared
        }
        return left.size.compareTo(right.size)
    }

    /**
     * `MAX_BODY_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private const val MAX_BODY_BYTES = 16 * 1024
    /**
     * `MIN_KEY_LENGTH`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private const val MIN_KEY_LENGTH = 8
    /**
     * `MAX_KEY_LENGTH`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private const val MAX_KEY_LENGTH = 200
    /**
     * `KEY_DOMAIN`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private const val KEY_DOMAIN = "voucher-http-idempotency-key-v1"
    /**
     * `REQUEST_DOMAIN`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private const val REQUEST_DOMAIN = "voucher-http-idempotency-request-v1"
    /**
     * `SEMANTIC_HEADERS`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val SEMANTIC_HEADERS = setOf("content-type", "x-workshop-principal", "x-workshop-tenant")
}

/**
 * canonicalDecimal은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 */
private fun canonicalDecimal(value: BigDecimal): String =
    value.stripTrailingZeros().let { if (it.compareTo(BigDecimal.ZERO) == 0) "0" else it.toPlainString() }

/**
 * jsonString은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 */
private fun jsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"'      -> append("\\\"")
            '\\'     -> append("\\\\")
            '\b'     -> append("\\b")
            '\u000C' -> append("\\f")
            '\n'     -> append("\\n")
            '\r'     -> append("\\r")
            '\t'     -> append("\\t")
            else     -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}

/**
 * encodeBase64Url은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 * @param bytes 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 */
private fun encodeBase64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/**
 * `SHA256_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val SHA256_BYTES = 32
/**
 * `SHA256_BASE64URL_LENGTH`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
 *
 * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
 */
private const val SHA256_BASE64URL_LENGTH = 43
/**
 * `OWNER_TOKEN_DOMAIN`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
 *
 * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
 */
private const val OWNER_TOKEN_DOMAIN = "voucher-http-idempotency-owner-v1"
/**
 * `BASE64URL`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
 *
 * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
 */
private val BASE64URL = Regex("[A-Za-z0-9_-]+")
