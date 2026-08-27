package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** 허용 목록 기반 broker 상태 요약만 기록하며 container log는 복사하지 않습니다. */
object KafkaFailoverBrokerSummaryWriter {
    fun write(
        runId: String,
        brokers: Collection<KafkaFailoverBrokerSnapshot>,
        root: Path = Path.of("build/reports/kafka-failover"),
    ) {
        require(RUN_ID_PATTERN.matches(runId)) { "runId contains unsafe path characters" }
        val runDirectory = root.resolve(runId).normalize()
        require(runDirectory.parent == root.normalize()) { "runId must remain within artifact root" }
        Files.createDirectories(runDirectory)
        brokers.sortedBy(KafkaFailoverBrokerSnapshot::nodeId).forEach { broker ->
            val target = runDirectory.resolve("broker-${broker.nodeId}.log")
            val temporary = runDirectory.resolve("broker-${broker.nodeId}.log.tmp")
            val summary = buildString {
                append("runId=").append(runId)
                append(" nodeId=").append(broker.nodeId)
                append(" alias=").append(broker.alias)
                append(" image=apache/kafka")
                append(" imageDigest=sha256:").append(KafkaFailoverClusterFixture.APPROVED_IMAGE_DIGEST)
                append(" running=").append(broker.isRunning)
                append('\n')
            }.toByteArray(StandardCharsets.UTF_8)
            try {
                FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ).use { channel ->
                    channel.write(java.nio.ByteBuffer.wrap(summary))
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
    }

    private val RUN_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
}
