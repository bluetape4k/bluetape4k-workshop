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
            ),
        )

    @Bean
    fun meterKafkaTemplate(producerFactory: ProducerFactory<String, String>): KafkaTemplate<String, String> =
        KafkaTemplate(producerFactory, true)
}

@ConfigurationProperties("usage-billing.meter.kafka")
data class MeterKafkaProperties(
    val bootstrapServers: String = "localhost:9092",
    val clientId: String = "usage-billing-meter-service",
)
