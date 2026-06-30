package io.bluetape4k.workshop.textmoderation

import io.bluetape4k.workshop.textmoderation.config.TextModerationProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * Spring Boot entrypoint for the text moderation API workshop module.
 */
@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = [TextModerationProperties::class])
class TextModerationApplication

fun main(args: Array<String>) {
    runApplication<TextModerationApplication>(*args)
}
