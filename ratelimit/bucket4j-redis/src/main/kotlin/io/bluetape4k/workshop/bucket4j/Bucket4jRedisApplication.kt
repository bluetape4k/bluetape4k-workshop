package io.bluetape4k.workshop.bucket4j

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.storage.RedisServer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching

/**
 * Spring Boot entry point for the Redis-backed Bucket4j WebFlux example.
 *
 * The companion starts the shared bluetape4k Redis Testcontainer so the sample
 * can bootstrap with the same Redis URL source used by its integration tests.
 */
@SpringBootApplication(proxyBeanMethods = false)
@EnableCaching
class RedisApplication {

    companion object : KLoggingChannel() {
        @JvmStatic
        private val redisServer = RedisServer.Launcher.redis
    }
}

fun main(vararg args: String) {
    runApplication<RedisApplication>(*args)
}
