package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** line-by-line artifact를 감사할 수 있도록 evidence phase 순서를 의도적으로 고정합니다. */
enum class KafkaFailoverPhase(val wireName: String) {
    STARTUP("startup"),
    TOPIC_READY("topic-ready"),
    ASSIGNMENT_READY("assignment-ready"),
    PREFIX_ACKED("prefix-acked"),
    FAULT_INJECTED("fault-injected"),
    RECOVERY("recovery"),
    SUFFIX_ACKED("suffix-acked"),
    REPLACEMENT_READY("replacement-ready"),
    ISR_RESTORED("isr-restored"),
    TERMINAL("terminal"),
    ;

    companion object {
        val ORDER: List<KafkaFailoverPhase> = entries
    }
}

/**
 * sanitize된 scalar-only evidence record입니다. event payload와 client endpoint를
 * 이 model이 받지 않아 CI에서 artifact를 안전하게 업로드할 수 있습니다.
 */
data class KafkaFailoverEvidence(
    val runId: String,
    val scenario: String,
    val phase: KafkaFailoverPhase,
    val image: String,
    val imageDigest: String,
    val topic: String,
    val partition: Int?,
    val nodeCount: Int?,
    val leader: Int?,
    val replicas: List<Int>,
    val isr: List<Int>,
    val coordinator: Int?,
    val assignmentCount: Int?,
    val rawDeliveryCount: Int?,
    val appliedCount: Int?,
    val conflictCount: Int?,
    val retryCount: Int?,
    val status: String,
) {
    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(scenario.isNotBlank()) { "scenario must not be blank" }
        require(image.isNotBlank()) { "image must not be blank" }
        require(imageDigest.matches(IMAGE_DIGEST_PATTERN)) { "imageDigest must be sha256 qualified" }
        require(topic == KafkaFailoverTopology.TOPIC) { "topic must be the reference topic" }
        require(status.isNotBlank()) { "status must not be blank" }
        listOf(assignmentCount, rawDeliveryCount, appliedCount, conflictCount, retryCount)
            .filterNotNull()
            .forEach { require(it >= 0) { "evidence counters must not be negative" } }
    }

    /** JSONL output에 사용하는 정확한 insertion order representation을 반환합니다. */
    fun toOrderedMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "runId" to runId,
        "scenario" to scenario,
        "phase" to phase.wireName,
        "image" to image,
        "imageDigest" to imageDigest,
        "topic" to topic,
        "partition" to partition,
        "nodeCount" to nodeCount,
        "leader" to leader,
        "replicas" to replicas,
        "isr" to isr,
        "coordinator" to coordinator,
        "assignmentCount" to assignmentCount,
        "rawDeliveryCount" to rawDeliveryCount,
        "appliedCount" to appliedCount,
        "conflictCount" to conflictCount,
        "retryCount" to retryCount,
        "status" to status,
    )

    fun toJsonLine(): String = JSON_MAPPER.writeValueAsString(toOrderedMap())

    companion object {
        const val PREFIX_EVENTS: Int = 4
        const val DATA_SUFFIX_EVENTS: Int = 4
        const val COORDINATOR_SUFFIX_EVENTS: Int = 2

        val FIELD_NAMES: List<String> = listOf(
            "runId",
            "scenario",
            "phase",
            "image",
            "imageDigest",
            "topic",
            "partition",
            "nodeCount",
            "leader",
            "replicas",
            "isr",
            "coordinator",
            "assignmentCount",
            "rawDeliveryCount",
            "appliedCount",
            "conflictCount",
            "retryCount",
            "status",
        )

        private val IMAGE_DIGEST_PATTERN = Regex("sha256:[0-9a-f]{64}")
        private val JSON_MAPPER: JsonMapper = JsonMapper.builder().build()

        fun exactLogicalIds(prefix: List<String>, suffix: List<String>): Set<String> {
            require(prefix.size == PREFIX_EVENTS) { "data prefix must contain $PREFIX_EVENTS events" }
            require(suffix.size == DATA_SUFFIX_EVENTS || suffix.size == COORDINATOR_SUFFIX_EVENTS) {
                "suffix must contain $DATA_SUFFIX_EVENTS or $COORDINATOR_SUFFIX_EVENTS events"
            }
            require(prefix.all(String::isNotBlank) && suffix.all(String::isNotBlank)) {
                "logical IDs must not be blank"
            }
            require(prefix.distinct().size == prefix.size && suffix.distinct().size == suffix.size) {
                "logical IDs must be unique within each batch"
            }
            require(prefix.toSet().intersect(suffix.toSet()).isEmpty()) {
                "prefix and suffix logical IDs must not overlap"
            }
            return (prefix + suffix).toSet()
        }
    }
}

/** scan result는 의도적으로 file path, hash, count만 노출합니다. */
data class KafkaFailoverArtifactScanResult(
    val scannedFiles: Int,
    val violations: List<String>,
    val rendered: String,
)

/**
 * evidence/JUnit/CI artifact directory를 fail-closed로 검사합니다.
 * 대소문자를 구분하지 않으며 URL decode한 content도 함께 검사합니다.
 */
object KafkaFailoverArtifactScanner {
    private val canaries = listOf(
        "payload",
        "eventid",
        "bootstrap.servers",
        "127.0.0.1:9092",
        "credential",
        "password",
        "secret",
        "owner-token",
        "kafk_", // 소문자 변환 후 KAFKA_ environment variable을 검출합니다.
        "exception",
        "stacktrace",
        "raw-log",
        "raw log",
        "java.lang.error",
    )

    fun scan(root: java.nio.file.Path): KafkaFailoverArtifactScanResult {
        require(java.nio.file.Files.exists(root)) { "artifact root does not exist" }
        val violations = mutableListOf<String>()
        var scanned = 0
        java.nio.file.Files.walk(root).use { paths ->
            paths.filter(java.nio.file.Files::isRegularFile).forEach { path ->
                scanned += 1
                val bytes = runCatching { java.nio.file.Files.readAllBytes(path) }.getOrElse { return@forEach }
                val content = bytes.toString(StandardCharsets.UTF_8)
                val normalized = decode(content).lowercase()
                val matched = canaries.count { normalized.contains(it) }
                if (matched > 0) {
                    violations += "${root.relativize(path)}:${sha256(bytes)}:$matched"
                }
            }
        }
        return KafkaFailoverArtifactScanResult(
            scannedFiles = scanned,
            violations = violations,
            rendered = violations.joinToString("\n"),
        )
    }

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrDefault(value)

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
}
