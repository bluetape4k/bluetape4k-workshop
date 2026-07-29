package io.bluetape4k.workshop.observability.advanced.config

import io.bluetape4k.redis.redisson.redissonClient
import io.bluetape4k.support.requireNotBlank
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Redis 연결을 위한 Redisson client 를 구성합니다.
 *
 * ## Behavior / Contract
 * - `url` property 는 Redisson `address` setter 가 요구하는 `redis://host:port` format 을 사용해야 합니다.
 * - application stop 시 bean 은 `shutdown()` 으로 destroy 됩니다.
 */
@Configuration(proxyBeanMethods = false)
class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    fun redissonClient(
        @Value("\${workshop.observability.redis.url}") url: String,
    ): RedissonClient {
        val redisUrl = url.requireNotBlank("url")
        return redissonClient {
            useSingleServer().address = redisUrl
        }
    }
}
