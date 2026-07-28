package io.bluetape4k.workshop.messaging.fallback.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * fallback outbox workshop module 을 위한 Kafka/Jackson configuration 입니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
class KafkaConfig {

    @Bean
    fun objectMapper(): ObjectMapper = jacksonObjectMapper()
}
