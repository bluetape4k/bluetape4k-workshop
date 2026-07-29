package io.bluetape4k.workshop.aws.sqssns

import io.bluetape4k.jackson3.Jackson
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import tools.jackson.databind.json.JsonMapper

/**
 * 메시징 서비스가 사용하는 bluetape4k Jackson 3 mapper를 등록합니다.
 */
@Configuration(proxyBeanMethods = false)
class JacksonMessagingConfig {

    @Bean
    @Primary
    fun objectMapper(): JsonMapper =
        Jackson.defaultJsonMapper
}
