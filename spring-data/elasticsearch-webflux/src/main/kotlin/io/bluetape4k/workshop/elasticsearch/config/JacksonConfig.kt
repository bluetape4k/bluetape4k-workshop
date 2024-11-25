package io.bluetape4k.workshop.elasticsearch.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.bluetape4k.jackson.Jackson
import io.bluetape4k.logging.KLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration
class JacksonConfig {

    companion object: KLogging()

    @Bean
    @Primary
    fun objectMapper(): ObjectMapper = Jackson.defaultJsonMapper
}
