package io.bluetape4k.workshop.jsonview.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.bluetape4k.jackson.Jackson
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JacksonConfig {

    @Bean
    fun objectMapper(): ObjectMapper = Jackson.defaultJsonMapper

}
