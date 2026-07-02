package io.bluetape4k.workshop.aws.sqssns

import io.bluetape4k.jackson3.Jackson
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import tools.jackson.databind.json.JsonMapper

/**
 * Registers the bluetape4k Jackson 3 mapper used by the messaging service.
 */
@Configuration(proxyBeanMethods = false)
class JacksonMessagingConfig {

    @Bean
    @Primary
    fun objectMapper(): JsonMapper =
        Jackson.defaultJsonMapper
}
