package io.bluetape4k.workshop.commerce.usagebilling.billing.config

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

@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(BillingKafkaProperties::class)
class BillingKafkaMessagingConfiguration {
    @Bean
    fun billingConsumerFactory(properties: BillingKafkaProperties): ConsumerFactory<String, String> =
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
    fun billingKafkaListenerContainerFactory(
        billingConsumerFactory: ConsumerFactory<String, String>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> =
        ConcurrentKafkaListenerContainerFactory<String, String>().also { factory ->
            factory.setConsumerFactory(billingConsumerFactory)
            factory.containerProperties.ackMode = ContainerProperties.AckMode.RECORD
        }
}

@ConfigurationProperties("usage-billing.billing.kafka")
data class BillingKafkaProperties(
    val bootstrapServers: String = "localhost:9092",
    val consumerGroup: String = "usage-billing-billing-service",
)
