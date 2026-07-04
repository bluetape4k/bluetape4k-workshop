package io.bluetape4k.workshop.bucket4j

import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching

/**
 * Spring Boot entry point for the local Caffeine-backed Bucket4j WebMVC example.
 *
 * The application enables Spring Cache so the Bucket4j starter can store servlet
 * rate-limit buckets in the configured Caffeine JCache cache.
 */
@SpringBootApplication(proxyBeanMethods = false)
@EnableCaching
class CaffeineApplication {
    companion object : KLogging()
}

fun main(vararg args: String) {
    runApplication<CaffeineApplication>(*args)
}
