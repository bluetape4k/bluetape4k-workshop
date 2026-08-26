package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/** sanitize된 failover evidence를 위한 atomic run-scoped JSONL writer입니다. */
class KafkaFailoverEvidenceWriter(
    private val runId: String,
    root: Path = Path.of("build/reports/kafka-failover"),
) : AutoCloseable {
    private val runDirectory: Path
    private val target: Path
    private var lastPhaseOrdinal: Int = -1
    private var closed: Boolean = false

    init {
        require(RUN_ID_PATTERN.matches(runId)) { "runId contains unsafe path characters" }
        runDirectory = root.resolve(runId).normalize()
        require(runDirectory.parent == root.normalize()) { "runId must remain within artifact root" }
        target = runDirectory.resolve("evidence.jsonl")
        Files.createDirectories(runDirectory)
        if (INITIALIZED_TARGETS.add(target.toAbsolutePath().normalize())) {
            Files.deleteIfExists(target)
        }
        Files.deleteIfExists(tempPath())
    }

    val artifact: Path
        get() = target

    fun append(evidence: KafkaFailoverEvidence) {
        check(!closed) { "evidence writer is closed" }
        require(evidence.runId == runId) { "evidence runId does not match writer" }
        require(evidence.phase.ordinal > lastPhaseOrdinal) {
            "evidence phase must advance monotonically"
        }

        val line = evidence.toJsonLine() + "\n"
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
            lastPhaseOrdinal = evidence.phase.ordinal
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun close() {
        closed = true
    }

    private fun tempPath(): Path = runDirectory.resolve("evidence.jsonl.tmp")

    companion object {
        private val RUN_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        /** test JVM마다 한 번 run을 비운 뒤 독립적인 scenario stream을 추가합니다. */
        private val INITIALIZED_TARGETS = ConcurrentHashMap.newKeySet<Path>()
    }
}
