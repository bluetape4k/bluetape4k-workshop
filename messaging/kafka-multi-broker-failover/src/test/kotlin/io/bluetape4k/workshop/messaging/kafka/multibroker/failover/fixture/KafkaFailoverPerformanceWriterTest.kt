package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import tools.jackson.databind.json.JsonMapper

class KafkaFailoverPerformanceWriterTest {

    @Test
    fun `performance writer emits ordered bounded counters and replaces current run`(@TempDir tempDir: Path) {
        val writer = KafkaFailoverPerformanceWriter("run-a", tempDir)
        writer.append(sample("startup"))
        writer.append(sample("terminal", elapsedMs = 42L))

        val artifact = tempDir.resolve("run-a/performance.jsonl")
        val rows = Files.readAllLines(artifact)
        rows.size shouldBeEqualTo 2
        JsonMapper.builder().build().readTree(rows.last()).propertyNames().asSequence().toList() shouldBeEqualTo
            listOf(
                "runId",
                "scenario",
                "phase",
                "elapsedMs",
                "deadlineRemainingMs",
                "adminRoundTripCount",
                "ackCount",
                "pollCount",
                "retryCount",
                "cleanupMs",
                "maxBufferedRecords",
                "maxBufferedBytes",
            )
        Files.exists(tempDir.resolve("run-a/performance.jsonl.tmp")).shouldBeFalse()
    }

    @Test
    fun `performance writer preserves both scenarios for one run`(@TempDir tempDir: Path) {
        KafkaFailoverPerformanceWriter("run-a", tempDir).use { writer ->
            writer.append(sample("terminal"))
        }
        KafkaFailoverPerformanceWriter("run-a", tempDir).use { writer ->
            writer.append(sample("terminal").copy(scenario = "group-coordinator-failover"))
        }

        Files.readAllLines(tempDir.resolve("run-a/performance.jsonl")).size shouldBeEqualTo 2
    }

    @Test
    fun `broker summary writer emits only allowlisted state`(@TempDir tempDir: Path) {
        KafkaFailoverBrokerSummaryWriter.write(
            runId = "run-a",
            brokers = listOf(
                KafkaFailoverBrokerSnapshot(
                    nodeId = 1,
                    alias = "kafka-1",
                    bootstrapServers = "127.0.0.1:39092",
                    imageReference = KafkaFailoverClusterFixture.IMAGE_REFERENCE,
                    repoDigests = listOf(KafkaFailoverClusterFixture.IMAGE_REFERENCE),
                    isRunning = true,
                ),
            ),
            root = tempDir,
        )

        val summary = Files.readString(tempDir.resolve("run-a/broker-1.log"))
        summary shouldBeEqualTo
            "runId=run-a nodeId=1 alias=kafka-1 image=apache/kafka " +
            "imageDigest=sha256:${KafkaFailoverClusterFixture.APPROVED_IMAGE_DIGEST} running=true\n"
        summary.contains("127.0.0.1").shouldBeFalse()
    }

    @Test
    fun `writers reject unsafe run ids`(@TempDir tempDir: Path) {
        assertFailsWith<IllegalArgumentException> { KafkaFailoverPerformanceWriter("../escape", tempDir) }
        assertFailsWith<IllegalArgumentException> {
            KafkaFailoverBrokerSummaryWriter.write("../escape", emptyList(), tempDir)
        }
        Files.exists(tempDir.resolve("escape")).shouldBeFalse()
    }

    private fun sample(phase: String, elapsedMs: Long = 0L) = KafkaFailoverPerformance(
        runId = "run-a",
        scenario = "data-leader-failover",
        phase = phase,
        elapsedMs = elapsedMs,
        deadlineRemainingMs = 1_000L,
        adminRoundTripCount = 3,
        ackCount = 4,
        pollCount = 5,
        retryCount = 1,
        cleanupMs = 6L,
        maxBufferedRecords = 1,
        maxBufferedBytes = 32L,
    )
}
