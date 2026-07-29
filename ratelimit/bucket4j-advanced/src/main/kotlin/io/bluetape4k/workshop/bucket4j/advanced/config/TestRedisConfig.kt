package io.bluetape4k.workshop.bucket4j.advanced.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.testcontainers.storage.RedisServer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order

/**
 * `dev`와 `test` profile에서 Testcontainers Redis singleton을 시작합니다.
 *
 * [LettuceConfig]가 `application.yml`의 `spring.data.redis.url`을 통해 연결할 수 있도록
 * container URL을 system property로 노출합니다.
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
