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
 * Redisson client 설정입니다.
 *
 * application property 에서 `spring.data.redis.host` 와 `spring.data.redis.port` 를 읽습니다.
 * 이 값은 test 시점에 [DynamicPropertySource] 로 override 됩니다.
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
