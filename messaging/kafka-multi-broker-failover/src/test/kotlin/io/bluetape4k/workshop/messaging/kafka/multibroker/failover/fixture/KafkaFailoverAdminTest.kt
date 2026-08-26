package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.apache.kafka.common.Node
import org.apache.kafka.common.TopicPartitionInfo
import org.junit.jupiter.api.Test

class KafkaFailoverAdminTest {

    @Test
    fun `partition summary contains only leader replicas and ISR IDs`() {
        val nodes = listOf(
            Node(1, "kafka-1", 9092),
            Node(2, "kafka-2", 9092),
            Node(3, "kafka-3", 9092),
        )
        val state = KafkaFailoverAdmin.toPartitionState(
            TopicPartitionInfo(0, nodes[1], listOf(nodes[2], nodes[1], nodes[0]), listOf(nodes[1], nodes[2])),
        )

        state.partition shouldBeEqualTo 0
        state.leader shouldBeEqualTo 2
        state.replicas shouldBeEqualTo listOf(1, 2, 3)
        state.isr shouldBeEqualTo listOf(2, 3)
    }

    @Test
    fun `allowlisted config names exclude raw broker config`() {
        KafkaFailoverAdmin.ALLOWLISTED_CONFIG_NAMES.contains("min.insync.replicas").shouldBeTrue()
        KafkaFailoverAdmin.ALLOWLISTED_CONFIG_NAMES.contains("listeners").shouldBeFalse()
        assertFailsWith<IllegalArgumentException> {
            KafkaFailoverEvidence(
                runId = "r",
                scenario = "s",
                phase = KafkaFailoverPhase.STARTUP,
                image = "apache/kafka",
                imageDigest = "sha256:${"a".repeat(64)}",
                topic = "other",
                partition = null,
                nodeCount = null,
                leader = null,
                replicas = emptyList(),
                isr = emptyList(),
                coordinator = null,
                assignmentCount = null,
                rawDeliveryCount = null,
                appliedCount = null,
                conflictCount = null,
                retryCount = null,
                status = "FAIL",
            )
        }
    }
}
