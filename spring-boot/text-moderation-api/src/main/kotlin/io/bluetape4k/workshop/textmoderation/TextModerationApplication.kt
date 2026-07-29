package io.bluetape4k.workshop.textmoderation

import io.bluetape4k.workshop.textmoderation.config.TextModerationProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * text moderation API workshop module 의 Spring Boot entrypoint 입니다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = [TextModerationProperties::class])
class TextModerationApplication

fun main(args: Array<String>) {
    runApplication<TextModerationApplication>(*args)
}
