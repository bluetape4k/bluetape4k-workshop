package io.bluetape4k.workshop.messaging.kafka.multibroker.failover

import io.bluetape4k.support.requireNotBlank
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.kafka.listener.ContainerProperties
import java.net.InetAddress

/**
 * Kafka failover reference가 사용하는 고정된 client property를 제공합니다.
 *
 * 이 설정은 테스트가 시작한 loopback broker만 허용하며, producer와 consumer가
 * 서로 다른 schema/type header 규칙을 암묵적으로 사용하지 않도록 명시적인
 * 문자열 serializer/deserializer를 사용합니다.
 */
class KafkaFailoverKafkaConfiguration(
    bootstrapServers: String,
    topic: String = KafkaFailoverEvent.TOPIC,
    groupId: String = GROUP_ID,
) {
    /** Kafka bootstrap endpoint 목록입니다. */
    val bootstrapServers: String = validateBootstrapServers(bootstrapServers)

    /** failover event가 기록되는 고정 topic입니다. */
    val topic: String = topic.requireNotBlank("topic").also {
        require(it == KafkaFailoverEvent.TOPIC) {
            "topic must be ${KafkaFailoverEvent.TOPIC}"
        }
    }

    /** failover consumer가 사용하는 고정 consumer group입니다. */
    val groupId: String = groupId.requireNotBlank("groupId").also {
        require(it == GROUP_ID) {
            "groupId must be $GROUP_ID"
        }
    }

    /** 수동 acknowledgment를 사용하는 listener mode입니다. */
    val listenerAckMode: ContainerProperties.AckMode = ContainerProperties.AckMode.MANUAL

    /**
     * producer 생성에 필요한 property를 반환합니다.
     *
     * @return mutation 없이 client factory에 전달할 수 있는 property map
     */
    fun producerProperties(): Map<String, Any> = mapOf(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java.name,
        ProducerConfig.ACKS_CONFIG to "all",
        ProducerConfig.RETRIES_CONFIG to PRODUCER_RETRIES,
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to PRODUCER_DELIVERY_TIMEOUT_MS,
        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to PRODUCER_REQUEST_TIMEOUT_MS,
        ProducerConfig.MAX_BLOCK_MS_CONFIG to PRODUCER_MAX_BLOCK_MS,
        ProducerConfig.RETRY_BACKOFF_MS_CONFIG to PRODUCER_RETRY_BACKOFF_MS,
        ProducerConfig.RETRY_BACKOFF_MAX_MS_CONFIG to PRODUCER_RETRY_BACKOFF_MAX_MS,
        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
        ProducerConfig.MAX_REQUEST_SIZE_CONFIG to MAX_REQUEST_BYTES,
    )

    /**
     * consumer 생성에 필요한 property를 반환합니다.
     *
     * @return mutation 없이 client factory에 전달할 수 있는 property map
     */
    fun consumerProperties(): Map<String, Any> = mapOf(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
        ConsumerConfig.GROUP_ID_CONFIG to groupId,
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG to CONSUMER_SESSION_TIMEOUT_MS,
        ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG to CONSUMER_HEARTBEAT_INTERVAL_MS,
        ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to CONSUMER_MAX_POLL_INTERVAL_MS,
        ConsumerConfig.MAX_POLL_RECORDS_CONFIG to CONSUMER_MAX_POLL_RECORDS,
        ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG to MAX_PARTITION_FETCH_BYTES,
        ConsumerConfig.FETCH_MAX_BYTES_CONFIG to FETCH_MAX_BYTES,
    )

    /**
     * topic 생성과 broker 상태 확인에 필요한 admin property를 반환합니다.
     *
     * @return mutation 없이 Kafka AdminClient에 전달할 수 있는 property map
     */
    fun adminProperties(): Map<String, Any> = mapOf(
        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
        AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG to ADMIN_REQUEST_TIMEOUT_MS,
        AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG to ADMIN_DEFAULT_API_TIMEOUT_MS,
    )

    companion object {
        const val GROUP_ID: String = "kafka-failover-reference-group"

        const val PRODUCER_RETRIES: Int = 3
        const val PRODUCER_DELIVERY_TIMEOUT_MS: Int = 20_000
        const val PRODUCER_REQUEST_TIMEOUT_MS: Int = 5_000
        const val PRODUCER_MAX_BLOCK_MS: Long = 10_000L
        const val PRODUCER_RETRY_BACKOFF_MS: Long = 200L
        const val PRODUCER_RETRY_BACKOFF_MAX_MS: Long = 2_000L

        const val CONSUMER_SESSION_TIMEOUT_MS: Int = 10_000
        const val CONSUMER_HEARTBEAT_INTERVAL_MS: Int = 3_000
        const val CONSUMER_MAX_POLL_INTERVAL_MS: Int = 20_000
        const val CONSUMER_MAX_POLL_RECORDS: Int = 100

        const val MAX_REQUEST_BYTES: Int = 1_048_576
        const val MAX_PARTITION_FETCH_BYTES: Int = 1_048_576
        const val FETCH_MAX_BYTES: Int = 4_194_304

        const val ADMIN_REQUEST_TIMEOUT_MS: Int = 5_000
        const val ADMIN_DEFAULT_API_TIMEOUT_MS: Int = 10_000

        val LOOPBACK_HOSTS: Set<String> = setOf("127.0.0.1", "localhost", "::1")

        fun validateBootstrapServers(value: String): String {
            val bootstrapServers = value.requireNotBlank("bootstrapServers")
            val endpoints = bootstrapServers.split(',')
            require(endpoints.all { it.isNotBlank() && it == it.trim() }) {
                "bootstrapServers must contain comma-separated loopback host:port endpoints"
            }

            endpoints.forEach(::validateEndpoint)
            return endpoints.joinToString(",")
        }

        fun validateEndpoint(endpoint: String) {
            require(
                endpoint.none { it == '/' || it == '?' || it == '#' || it == '@' } &&
                    !endpoint.contains("://"),
            ) {
                "bootstrapServers must contain plain host:port endpoints"
            }

            val parsed = parseEndpoint(endpoint)
            require(parsed != null) {
                "bootstrapServers must contain host:port endpoints"
            }
            val (host, port) = parsed
            require(host in LOOPBACK_HOSTS) {
                "bootstrapServers must use a loopback host"
            }
            if (host == "localhost") {
                val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull()
                require(!addresses.isNullOrEmpty() && addresses.all(InetAddress::isLoopbackAddress)) {
                    "localhost must resolve only to loopback addresses"
                }
            }
            require(port in 1..65_535) {
                "bootstrapServers port must be between 1 and 65535"
            }
        }

        fun parseEndpoint(endpoint: String): Pair<String, Int>? {
            if (endpoint.startsWith('[')) {
                val closingBracket = endpoint.indexOf(']')
                if (closingBracket <= 1 || closingBracket + 1 >= endpoint.length ||
                    endpoint[closingBracket + 1] != ':'
                ) {
                    return null
                }
                val host = endpoint.substring(1, closingBracket)
                val port = endpoint.substring(closingBracket + 2).toIntOrNull() ?: return null
                return host to port
            }

            val separator = endpoint.lastIndexOf(':')
            if (separator <= 0 || separator != endpoint.indexOf(':')) {
                return null
            }
            val host = endpoint.substring(0, separator)
            val port = endpoint.substring(separator + 1).toIntOrNull() ?: return null
            return host to port
        }
    }
}
