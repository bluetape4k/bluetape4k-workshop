package io.bluetape4k.workshop.messaging.outbox.config

import io.bluetape4k.logging.KLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Transactional Outbox pattern 을 위한 Kafka 및 Jackson configuration 입니다.
 *
 * `KafkaTemplate` 은 `spring.kafka.*` property 로 Spring Boot 가 auto-configure 합니다. domain service 가 event payload 를 JSON 으로 serialize 할 수 있도록 Kotlin Jackson module 을 사용하는 명시적 `ObjectMapper` bean 을 등록합니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
class KafkaConfig {

    companion object : KLogging()

    @Bean
    fun objectMapper(): ObjectMapper = jacksonObjectMapper()
}
