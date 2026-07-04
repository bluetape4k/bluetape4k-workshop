package io.bluetape4k.workshop.imageprocessing.profile

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
/**
 * Spring Boot entrypoint for the profile-image moderation workshop example.
 */
class ProfileImageModerationApplication

/**
 * Runs the profile-image moderation example application.
 */
fun main(args: Array<String>) {
    runApplication<ProfileImageModerationApplication>(*args)
}
