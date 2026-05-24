package io.bluetape4k.workshop.bucket4j.advanced.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.testcontainers.storage.RedisServer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order

/**
 * Starts a Testcontainers Redis singleton for `dev` and `test` profiles.
 *
 * Exposes the container URL via system properties so that [LettuceConfig] can wire it
 * through `spring.data.redis.url` in `application.yml`.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev", "test")
@Order(0)
class TestRedisConfig {

    companion object : KLoggingChannel() {
        val redis: RedisServer = RedisServer.Launcher.redis

        init {
            System.setProperty("testcontainers.redis.url", redis.url)
            System.setProperty("testcontainers.redis.host", redis.host)
            System.setProperty("testcontainers.redis.port", redis.port.toString())
        }
    }

    @Bean
    fun redisServer(): RedisServer {
        log.info { "Redis Server url=${redis.url}" }
        return redis
    }
}
