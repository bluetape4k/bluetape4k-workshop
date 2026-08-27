package io.bluetape4k.workshop.messaging.kafka.multibroker.failover

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverArtifactScanner
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverEvidence
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverEvidenceWriter
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverPhase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

class KafkaFailoverEvidenceTest {

    @Test
    fun `evidence line has exactly the ordered eighteen fields`() {
        val evidence = sample(KafkaFailoverPhase.STARTUP)
        val line = evidence.toJsonLine()
        val node = JsonMapper.builder().build().readTree(line)

        node.propertyNames().asSequence().toList() shouldBeEqualTo KafkaFailoverEvidence.FIELD_NAMES
        node.size() shouldBeEqualTo 18
        line.contains("payload-should-not-appear").shouldBeFalse()
        line.contains("127.0.0.1:9092").shouldBeFalse()
    }

    @Test
    fun `phase order and exact scenario invariants are fixed`() {
        KafkaFailoverPhase.ORDER.map(KafkaFailoverPhase::wireName) shouldBeEqualTo listOf(
            "startup",
            "topic-ready",
            "assignment-ready",
            "prefix-acked",
            "fault-injected",
            "recovery",
            "suffix-acked",
            "replacement-ready",
            "isr-restored",
            "terminal",
        )
        KafkaFailoverEvidence.PREFIX_EVENTS shouldBeEqualTo 4
        KafkaFailoverEvidence.DATA_SUFFIX_EVENTS shouldBeEqualTo 4
        KafkaFailoverEvidence.COORDINATOR_SUFFIX_EVENTS shouldBeEqualTo 2

        val ids = KafkaFailoverEvidence.exactLogicalIds(
            prefix = listOf("prefix-0", "prefix-1", "prefix-2", "prefix-3"),
            suffix = listOf("suffix-0", "suffix-1", "suffix-2", "suffix-3"),
        )
        ids shouldBeEqualTo (0..7).map { index -> if (index < 4) "prefix-$index" else "suffix-${index - 4}" }.toSet()
    }

    @Test
    fun `writer atomically replaces only the current run and keeps phase sequence`(@TempDir tempDir: Path) {
        val writer = KafkaFailoverEvidenceWriter("run-a", tempDir)
        writer.append(sample(KafkaFailoverPhase.STARTUP))
        writer.append(sample(KafkaFailoverPhase.TOPIC_READY))
        val artifact = tempDir.resolve("run-a/evidence.jsonl")
        Files.readAllLines(artifact).size shouldBeEqualTo 2
        Files.exists(tempDir.resolve("run-a/evidence.jsonl.tmp")).shouldBeFalse()

        val otherRun = KafkaFailoverEvidenceWriter("run-b", tempDir)
        otherRun.append(sample(KafkaFailoverPhase.STARTUP, runId = "run-b"))
        Files.readAllLines(artifact).all { it.contains("\"runId\":\"run-a\"") }.shouldBeTrue()

        assertFailsWith<IllegalArgumentException> {
            writer.append(sample(KafkaFailoverPhase.STARTUP))
        }
    }

    @Test
    fun `artifact scanner fails closed on canary variants without echoing secrets`(@TempDir tempDir: Path) {
        val clean = tempDir.resolve("clean.jsonl")
        Files.writeString(clean, sample(KafkaFailoverPhase.STARTUP).toJsonLine())
        KafkaFailoverArtifactScanner.scan(tempDir).violations shouldBeEqualTo emptyList()

        val contaminated = tempDir.resolve("contaminated.log")
        Files.writeString(contaminated, "payload-1 bootstrap.servers=127.0.0.1:9092 exception=java.lang.Error")
        val result = KafkaFailoverArtifactScanner.scan(tempDir)
        result.violations.isNotEmpty().shouldBeTrue()
        result.rendered.contains("payload-1").shouldBeFalse()
        result.rendered.contains("127.0.0.1:9092").shouldBeFalse()
    }

    private fun sample(
        phase: KafkaFailoverPhase,
        runId: String = "run-a",
    ) = KafkaFailoverEvidence(
        runId = runId,
        scenario = "data-leader-failover",
        phase = phase,
        image = "apache/kafka",
        imageDigest = "sha256:${"a".repeat(64)}",
        topic = KafkaFailoverEvent.TOPIC,
        partition = 0,
        nodeCount = 3,
        leader = 1,
        replicas = listOf(1, 2, 3),
        isr = listOf(1, 2, 3),
        coordinator = 2,
        assignmentCount = 1,
        rawDeliveryCount = 4,
        appliedCount = 4,
        conflictCount = 0,
        retryCount = 0,
        status = "PASS",
    )
}
