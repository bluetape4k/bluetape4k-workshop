package io.bluetape4k.workshop.elasticsearch.config

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import tools.jackson.databind.json.JsonMapper

@Configuration
class JacksonConfig {

    companion object: KLogging()

    @Bean
    @Primary
    fun objectMapper(): JsonMapper = Jackson.defaultJsonMapper
}
