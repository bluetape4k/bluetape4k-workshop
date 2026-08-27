package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

class KafkaFailoverTopologyTest {

    @Test
    fun `three broker kraft topology uses stable aliases and voters`() {
        KafkaFailoverTopology.NODE_IDS shouldBeEqualTo listOf(1, 2, 3)
        KafkaFailoverTopology.controllerQuorumVoters() shouldBeEqualTo
            "1@kafka-1:9094,2@kafka-2:9094,3@kafka-3:9094"

        KafkaFailoverTopology.NODE_IDS.forEach { nodeId ->
            val environment = KafkaFailoverTopology.brokerEnvironment(nodeId)
            environment["KAFKA_NODE_ID"] shouldBeEqualTo nodeId.toString()
            environment["KAFKA_CONTROLLER_QUORUM_VOTERS"] shouldBeEqualTo
                "1@kafka-1:9094,2@kafka-2:9094,3@kafka-3:9094"
            environment["KAFKA_LISTENERS"] shouldBeEqualTo
                "PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://0.0.0.0:9094"
            environment["KAFKA_AUTO_CREATE_TOPICS_ENABLE"] shouldBeEqualTo "false"
            environment["KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR"] shouldBeEqualTo "3"
        }
    }

    @Test
    fun `only loopback mapped plaintext listener is valid for host clients`() {
        KafkaFailoverTopology.isHostAdvertisedListener("PLAINTEXT://127.0.0.1:39123").shouldBeTrue()
        KafkaFailoverTopology.isHostAdvertisedListener("PLAINTEXT://localhost:39123").shouldBeTrue()

        KafkaFailoverTopology.isHostAdvertisedListener("BROKER://kafka-1:9093").shouldBeFalse()
        KafkaFailoverTopology.isHostAdvertisedListener("CONTROLLER://kafka-1:9094").shouldBeFalse()
        KafkaFailoverTopology.isHostAdvertisedListener("PLAINTEXT://0.0.0.0:9092").shouldBeFalse()
        KafkaFailoverTopology.isHostAdvertisedListener("PLAINTEXT://10.0.0.4:9092").shouldBeFalse()
    }

    @Test
    fun `unknown broker node is rejected before container creation`() {
        assertFailsWith<IllegalArgumentException> {
            KafkaFailoverTopology.brokerAlias(99)
        }
    }
}
