package io.bluetape4k.workshop.messaging.fallback.config

import io.bluetape4k.jackson3.Jackson
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.ObjectMapper

/**
 * fallback outbox workshop module 을 위한 Kafka/Jackson configuration 입니다.
 *
 * Bluetape Jackson3의 module/Kotlin 기준을 재사용하되 Spring HTTP 입력 계약은 기존과 동일하게 unknown property를 허용하고,
 * trailing token과 trailing comma를 거부합니다. 공유 singleton을 변경하지 않도록 독립 mapper를 생성합니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
class KafkaConfig {

    @Bean
    fun objectMapper(): ObjectMapper =
        Jackson.createDefaultJsonMapper()
            .rebuild()
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build()
}
