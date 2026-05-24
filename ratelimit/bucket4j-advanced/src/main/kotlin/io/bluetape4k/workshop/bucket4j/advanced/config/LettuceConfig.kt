package io.bluetape4k.workshop.bucket4j.advanced.config

import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.support.uninitialized
import io.lettuce.core.RedisClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Provides a Lettuce [RedisClient] bean wired from `spring.data.redis.url`.
 */
@Configuration(proxyBeanMethods = false)
class LettuceConfig {

    @Value("\${spring.data.redis.url}")
    private val redisUrl: String = uninitialized()

    @Bean(destroyMethod = "shutdown")
    fun redisClient(): RedisClient {
        return LettuceClients.clientOf(redisUrl)
    }
}
