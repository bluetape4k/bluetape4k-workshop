package io.bluetape4k.workshop.idempotency

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Idempotency Key workshop application.
 *
 * Demonstrates duplicate-safe command handling using Redis (Redisson RMapCache)
 * and Spring Boot WebFlux with Kotlin coroutines.
 */
@SpringBootApplication(proxyBeanMethods = false)
class IdempotencyApplication {

    companion object : KLogging() {
        /**
         * Singleton Redis server started via Testcontainers for local development and tests.
         */
        @JvmStatic
        val redisServer: RedisServer = RedisServer.Launcher.redis
    }
}

fun main(args: Array<String>) {
    runApplication<IdempotencyApplication>(*args)
}
