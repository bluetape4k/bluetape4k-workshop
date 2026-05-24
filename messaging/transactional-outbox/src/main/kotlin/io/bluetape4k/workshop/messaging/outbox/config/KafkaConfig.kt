package io.bluetape4k.workshop.messaging.outbox.config

import io.bluetape4k.logging.KLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Kafka and Jackson configuration for the Transactional Outbox pattern.
 *
 * `KafkaTemplate` is auto-configured by Spring Boot from `spring.kafka.*` properties.
 * An explicit `ObjectMapper` bean is registered using the Kotlin Jackson module so
 * domain services can serialize event payloads to JSON.
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
class KafkaConfig {

    companion object : KLogging()

    @Bean
    fun objectMapper(): ObjectMapper = jacksonObjectMapper()
}
