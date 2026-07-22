package io.bluetape4k.workshop.commerce.usagebilling.meter.config

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory

@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(MeterKafkaProperties::class)
class MeterKafkaMessagingConfiguration {
    @Bean
    fun meterProducerFactory(properties: MeterKafkaProperties): ProducerFactory<String, String> =
        DefaultKafkaProducerFactory(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to properties.bootstrapServers,
                ProducerConfig.CLIENT_ID_CONFIG to properties.clientId,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.ACKS_CONFIG to "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
                ProducerConfig.MAX_BLOCK_MS_CONFIG to PRODUCER_FAILURE_TIMEOUT_MS,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to PRODUCER_REQUEST_TIMEOUT_MS,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to PRODUCER_FAILURE_TIMEOUT_MS,
            ),
        )

    @Bean
    fun meterKafkaTemplate(producerFactory: ProducerFactory<String, String>): KafkaTemplate<String, String> =
        KafkaTemplate(producerFactory, true)

    private companion object {
        const val PRODUCER_FAILURE_TIMEOUT_MS = 5_000
        const val PRODUCER_REQUEST_TIMEOUT_MS = 3_000
    }
}

@ConfigurationProperties("usage-billing.meter.kafka")
data class MeterKafkaProperties(
    val bootstrapServers: String = "localhost:9092",
    val clientId: String = "usage-billing-meter-service",
)
