package io.bluetape4k.workshop.jsonview.config

import io.bluetape4k.jackson3.Jackson
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper

@Configuration
class JacksonConfig {

    @Bean
    fun jsonMapper(): JsonMapper = Jackson.defaultJsonMapper

}
