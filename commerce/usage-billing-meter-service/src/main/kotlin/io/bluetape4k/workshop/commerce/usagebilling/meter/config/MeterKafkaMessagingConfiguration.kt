package io.bluetape4k.workshop.commerce.usagebilling.meter.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory

@Configuration(proxyBeanMethods = false)
@EnableKafka
class MeterKafkaMessagingConfiguration {
    @Bean
    fun meterProducerFactory(): ProducerFactory<String, String> = DefaultKafkaProducerFactory(emptyMap())

    @Bean
    fun meterKafkaTemplate(producerFactory: ProducerFactory<String, String>): KafkaTemplate<String, String> =
        KafkaTemplate(producerFactory, true)
}
