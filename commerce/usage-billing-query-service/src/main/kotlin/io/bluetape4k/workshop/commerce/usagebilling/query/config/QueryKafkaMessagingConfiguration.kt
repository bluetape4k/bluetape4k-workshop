package io.bluetape4k.workshop.commerce.usagebilling.query.config

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ContainerProperties
import java.io.Serializable

@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(QueryKafkaProperties::class)
class QueryKafkaMessagingConfiguration {
    @Bean
    fun queryConsumerFactory(properties: QueryKafkaProperties): ConsumerFactory<String, String> =
        DefaultKafkaConsumerFactory(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to properties.bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to properties.consumerGroup,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ),
        )

    @Bean
    fun queryKafkaListenerContainerFactory(
        queryConsumerFactory: ConsumerFactory<String, String>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> =
        ConcurrentKafkaListenerContainerFactory<String, String>().also { factory ->
            factory.setConsumerFactory(queryConsumerFactory)
            factory.containerProperties.ackMode = ContainerProperties.AckMode.RECORD
        }
}

@ConfigurationProperties("usage-billing.query.kafka")
data class QueryKafkaProperties(
    val bootstrapServers: String = "localhost:9092",
    val consumerGroup: String = "usage-billing-query-service",
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}
