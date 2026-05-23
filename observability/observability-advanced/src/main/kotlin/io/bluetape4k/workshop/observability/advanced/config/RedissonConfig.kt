package io.bluetape4k.workshop.observability.advanced.config

import io.bluetape4k.redis.redisson.redissonClient
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configures the Redisson client for Redis connectivity.
 *
 * ## Behavior / Contract
 * - The `url` property must use the `redis://host:port` format required by Redisson's `address` setter.
 * - Bean is destroyed via `shutdown()` on application stop.
 */
@Configuration(proxyBeanMethods = false)
class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    fun redissonClient(
        @Value("\${workshop.observability.redis.url}") url: String,
    ): RedissonClient = redissonClient {
        useSingleServer().address = url
    }
}
