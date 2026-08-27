package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import tools.jackson.databind.json.JsonMapper
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/** 18-field evidence와 분리해 보관하는 허용 목록 timing/counter observation입니다. */
data class KafkaFailoverPerformance(
    val runId: String,
    val scenario: String,
    val phase: String,
    val elapsedMs: Long,
    val deadlineRemainingMs: Long,
    val adminRoundTripCount: Int,
    val ackCount: Int,
    val pollCount: Int,
    val retryCount: Int,
    val cleanupMs: Long,
    val maxBufferedRecords: Int,
    val maxBufferedBytes: Long,
) {
    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(scenario.isNotBlank()) { "scenario must not be blank" }
        require(phase.isNotBlank()) { "phase must not be blank" }
        require(elapsedMs >= 0L) { "elapsedMs must not be negative" }
        require(deadlineRemainingMs >= 0L) { "deadlineRemainingMs must not be negative" }
        require(adminRoundTripCount >= 0) { "adminRoundTripCount must not be negative" }
        require(ackCount >= 0) { "ackCount must not be negative" }
        require(pollCount >= 0) { "pollCount must not be negative" }
        require(retryCount >= 0) { "retryCount must not be negative" }
        require(cleanupMs >= 0L) { "cleanupMs must not be negative" }
        require(maxBufferedRecords >= 0) { "maxBufferedRecords must not be negative" }
        require(maxBufferedBytes >= 0L) { "maxBufferedBytes must not be negative" }
    }

    fun toOrderedMap(): LinkedHashMap<String, Any> = linkedMapOf(
        "runId" to runId,
        "scenario" to scenario,
        "phase" to phase,
        "elapsedMs" to elapsedMs,
        "deadlineRemainingMs" to deadlineRemainingMs,
        "adminRoundTripCount" to adminRoundTripCount,
        "ackCount" to ackCount,
        "pollCount" to pollCount,
        "retryCount" to retryCount,
        "cleanupMs" to cleanupMs,
        "maxBufferedRecords" to maxBufferedRecords,
        "maxBufferedBytes" to maxBufferedBytes,
    )

    fun toJsonLine(): String = JSON_MAPPER.writeValueAsString(toOrderedMap())

    private companion object {
        val JSON_MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}

/** 제한된 performance observation stream을 위한 atomic run-scoped writer입니다. */
class KafkaFailoverPerformanceWriter(
    private val runId: String,
    root: Path = Path.of("build/reports/kafka-failover"),
) : AutoCloseable {
    private val runDirectory: Path
    private val target: Path
    private var closed = false

    init {
        require(RUN_ID_PATTERN.matches(runId)) { "runId contains unsafe path characters" }
        runDirectory = root.resolve(runId).normalize()
        require(runDirectory.parent == root.normalize()) { "runId must remain within artifact root" }
        target = runDirectory.resolve("performance.jsonl")
        Files.createDirectories(runDirectory)
        if (INITIALIZED_TARGETS.add(target.toAbsolutePath().normalize())) {
            Files.deleteIfExists(target)
        }
        Files.deleteIfExists(tempPath())
    }

    fun append(observation: KafkaFailoverPerformance) {
        check(!closed) { "performance writer is closed" }
        require(observation.runId == runId) { "performance runId does not match writer" }

        val line = observation.toJsonLine() + "\n"
        val previous = if (Files.exists(target)) Files.readAllBytes(target) else ByteArray(0)
        val next = previous + line.toByteArray(StandardCharsets.UTF_8)
        val temporary = tempPath()
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(next))
                channel.force(true)
            }
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun close() {
        closed = true
    }

    private fun tempPath(): Path = runDirectory.resolve("performance.jsonl.tmp")

    private companion object {
        val RUN_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        /** test JVM마다 한 번 run을 비운 뒤 독립적인 scenario stream을 추가합니다. */
        val INITIALIZED_TARGETS = ConcurrentHashMap.newKeySet<Path>()
    }
}
