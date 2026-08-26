package io.bluetape4k.workshop.messaging.kafka.multibroker.failover

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.springframework.kafka.listener.ContainerProperties

/**
 * producer, consumer, admin client에 전달되는 명시적 Kafka property를 검증합니다.
 */
class KafkaFailoverKafkaConfigurationTest {

    @Test
    fun `configuration exposes strict producer consumer and admin properties`() {
        val configuration = configuration()
        val producer = configuration.producerProperties()
        val consumer = configuration.consumerProperties()
        val admin = configuration.adminProperties()

        producer[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] shouldBeEqualTo "127.0.0.1:19092"
        producer[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] shouldBeEqualTo StringSerializer::class.java.name
        producer[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] shouldBeEqualTo StringSerializer::class.java.name
        producer[ProducerConfig.ACKS_CONFIG] shouldBeEqualTo "all"
        producer[ProducerConfig.RETRIES_CONFIG] shouldBeEqualTo 3
        producer[ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG] shouldBeEqualTo 20_000
        producer[ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG] shouldBeEqualTo 5_000
        producer[ProducerConfig.MAX_BLOCK_MS_CONFIG] shouldBeEqualTo 10_000L
        producer[ProducerConfig.RETRY_BACKOFF_MS_CONFIG] shouldBeEqualTo 200L
        producer[ProducerConfig.RETRY_BACKOFF_MAX_MS_CONFIG] shouldBeEqualTo 2_000L
        producer[ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG] shouldBeEqualTo true
        (producer.keys.none { it.contains("type", ignoreCase = true) }).shouldBeTrue()

        consumer[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] shouldBeEqualTo "127.0.0.1:19092"
        consumer[ConsumerConfig.GROUP_ID_CONFIG] shouldBeEqualTo KafkaFailoverKafkaConfiguration.GROUP_ID
        consumer[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] shouldBeEqualTo StringDeserializer::class.java.name
        consumer[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] shouldBeEqualTo StringDeserializer::class.java.name
        consumer[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] shouldBeEqualTo false
        consumer[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] shouldBeEqualTo "earliest"
        consumer[ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG] shouldBeEqualTo 10_000
        consumer[ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG] shouldBeEqualTo 3_000
        consumer[ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG] shouldBeEqualTo 20_000
        (consumer.keys.none { it.contains("type", ignoreCase = true) }).shouldBeTrue()

        admin[AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG] shouldBeEqualTo "127.0.0.1:19092"
        admin[AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG] shouldBeEqualTo 5_000
        admin[AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG] shouldBeEqualTo 10_000

        configuration.listenerAckMode shouldBeEqualTo ContainerProperties.AckMode.MANUAL
    }

    @Test
    fun `configuration rejects empty or non loopback bootstrap endpoints`() {
        listOf(
            "",
            " ",
            "kafka.example.com:9092",
            "10.0.0.1:9092",
            "172.16.0.1:9092",
            "192.168.1.10:9092",
            "http://127.0.0.1:9092",
            "user:password@127.0.0.1:9092",
            "127.0.0.1:9092/path",
            "127.0.0.1:not-a-port",
            "127.0.0.1:0",
            "127.0.0.1:65536",
        ).forEach { bootstrap ->
            assertFailsWith<IllegalArgumentException> {
                KafkaFailoverKafkaConfiguration(
                    bootstrapServers = bootstrap,
                    topic = KafkaFailoverEvent.TOPIC,
                    groupId = KafkaFailoverKafkaConfiguration.GROUP_ID,
                )
            }
        }
    }

    @Test
    fun `configuration rejects mutable reference topic and group`() {
        assertFailsWith<IllegalArgumentException> {
            KafkaFailoverKafkaConfiguration(
                bootstrapServers = "127.0.0.1:19092",
                topic = "another-topic",
                groupId = KafkaFailoverKafkaConfiguration.GROUP_ID,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            KafkaFailoverKafkaConfiguration(
                bootstrapServers = "127.0.0.1:19092",
                topic = KafkaFailoverEvent.TOPIC,
                groupId = "another-group",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            KafkaFailoverKafkaConfiguration(
                bootstrapServers = "127.0.0.1:19092",
                topic = KafkaFailoverEvent.TOPIC,
                groupId = " ",
            )
        }
    }

    @Test
    fun `configuration accepts loopback endpoint lists`() {
        val configuration = KafkaFailoverKafkaConfiguration(
            bootstrapServers = "localhost:19092,127.0.0.1:19093",
            topic = KafkaFailoverEvent.TOPIC,
            groupId = KafkaFailoverKafkaConfiguration.GROUP_ID,
        )

        configuration.bootstrapServers shouldBeEqualTo "localhost:19092,127.0.0.1:19093"
        configuration.topic shouldBeEqualTo KafkaFailoverEvent.TOPIC
        configuration.groupId shouldBeEqualTo KafkaFailoverKafkaConfiguration.GROUP_ID
        configuration.producerProperties().containsKey("spring.json.add.type.headers").shouldBeFalse()
    }

    private fun configuration() = KafkaFailoverKafkaConfiguration(
        bootstrapServers = "127.0.0.1:19092",
        topic = KafkaFailoverEvent.TOPIC,
        groupId = KafkaFailoverKafkaConfiguration.GROUP_ID,
    )
}
