package io.bluetape4k.workshop.bucket4j.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.lettuce.core.RedisClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Provides the Lettuce [RedisClient] used by the Bucket4j Redis proxy manager.
 *
 * The URL comes from the bluetape4k Redis Testcontainer launcher through the
 * `testcontainers.redis.url` system property.
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
