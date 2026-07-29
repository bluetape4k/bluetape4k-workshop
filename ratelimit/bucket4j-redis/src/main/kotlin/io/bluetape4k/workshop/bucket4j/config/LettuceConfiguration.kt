package io.bluetape4k.workshop.bucket4j.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.lettuce.core.RedisClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Bucket4j Redis proxy manager가 사용하는 Lettuce [RedisClient]를 제공합니다.
 *
 * URL은 bluetape4k Redis Testcontainer launcher가 설정한
 * `testcontainers.redis.url` system property에서 가져옵니다.
 */
@Configuration(proxyBeanMethods = false)
class LettuceConfiguration {

    companion object : KLoggingChannel()

    @Bean
    fun redisClient(): RedisClient {
        val url = System.getProperty("testcontainers.redis.url")
            .requireNotBlank("testcontainers.redis.url")
        log.debug { "Create RedisClient. url=$url" }
        return RedisClient.create(url)
    }
}
