package io.bluetape4k.workshop.messaging.outbox.config

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.ObjectMapper

/**
 * Transactional Outbox pattern 을 위한 Kafka 및 Jackson configuration 입니다.
 *
 * `KafkaTemplate` 은 `spring.kafka.*` property 로 Spring Boot 가 auto-configure 합니다. domain service 가 event payload 를
 * JSON 으로 serialize 할 수 있도록 Bluetape Jackson3 module/Kotlin 기준을 재사용하는 명시적 `ObjectMapper` bean 을 등록합니다.
 * Spring HTTP 입력 계약은 기존과 동일하게 unknown property를 허용하고 trailing token과 trailing comma를 거부하며,
 * 공유 singleton은 변경하지 않습니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
class KafkaConfig {

    companion object : KLogging()

    @Bean
    fun objectMapper(): ObjectMapper =
        Jackson.createDefaultJsonMapper()
            .rebuild()
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build()
}
