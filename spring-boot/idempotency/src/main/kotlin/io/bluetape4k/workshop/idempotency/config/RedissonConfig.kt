package io.bluetape4k.workshop.idempotency.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.uninitialized
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Redisson client configuration.
 *
 * Reads `spring.data.redis.host` and `spring.data.redis.port` from application properties,
 * which are overridden at test time via [DynamicPropertySource].
 */
@Configuration(proxyBeanMethods = false)
class RedissonConfig {

    companion object : KLogging()

    @Value("\${spring.data.redis.host:localhost}")
    private val redisHost: String = uninitialized()

    @Value("\${spring.data.redis.port:6379}")
    private val redisPort: Int = 6379

    @Bean(destroyMethod = "shutdown")
    fun redissonClient(): RedissonClient {
        val address = "redis://$redisHost:$redisPort"
        log.info { "Creating Redisson client for $address" }

        val config = Config().apply {
            useSingleServer()
                .setAddress(address)
                .setConnectionPoolSize(32)
                .setConnectionMinimumIdleSize(8)
                .setTimeout(3000)
                .setRetryAttempts(3)
        }
        return Redisson.create(config)
    }
}
