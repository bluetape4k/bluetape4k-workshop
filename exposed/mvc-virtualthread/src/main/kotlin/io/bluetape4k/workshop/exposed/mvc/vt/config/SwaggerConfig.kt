package io.bluetape4k.workshop.exposed.mvc.vt.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class SwaggerConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("Exposed MVC Virtual Thread API")
            .description("Spring MVC + Virtual Threads + Exposed JDBC example with Author/Book + Order domains")
            .version("1.0.0")
    )
}
