package io.bluetape4k.workshop.commerce.usagebilling.billing.config

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.ContainerProperties
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(BillingKafkaProperties::class)
class BillingKafkaMessagingConfiguration {
    @Bean
    fun billingClock(): Clock = Clock.systemUTC()

    @Bean
    fun billingProducerFactory(properties: BillingKafkaProperties): ProducerFactory<String, String> =
        DefaultKafkaProducerFactory(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to properties.bootstrapServers,
                ProducerConfig.CLIENT_ID_CONFIG to properties.producerClientId,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.ACKS_CONFIG to "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
            ),
        )

    @Bean
    fun billingKafkaTemplate(producerFactory: ProducerFactory<String, String>): KafkaTemplate<String, String> =
        KafkaTemplate(producerFactory, true)

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
    val producerClientId: String = "usage-billing-billing-service",
)
