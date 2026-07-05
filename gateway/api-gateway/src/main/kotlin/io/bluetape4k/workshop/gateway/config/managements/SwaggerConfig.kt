package io.bluetape4k.workshop.gateway.config.managements

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Swagger 설정을 위한 Configuration
 */
@Configuration(proxyBeanMethods = false)
class SwaggerConfig(
    private val buildProps: BuildProperties,
) {

    @Bean
    fun apiInfo(): OpenAPI {
        return OpenAPI().info(
            Info().title(buildProps.name)
                .description("Bluetape4k 서비스에서 공용으로 사용하는 서비스를 제공합니다.")
                .version(buildProps.version)
                .contact(contact)
                .license(license)
        )
    }

    private val contact =
        Contact()
            .name("Bluetape4k Kotlin Workshop")
            .email("sunghyouk.bae@gmail.com")
            .url("https://github.com/bluetape4k/bluetape4k-workshop")

    private val license =
        License()
            .name("Bluetape4k License 1.0")
            .url("https://bluetape4k.io/license")
}
