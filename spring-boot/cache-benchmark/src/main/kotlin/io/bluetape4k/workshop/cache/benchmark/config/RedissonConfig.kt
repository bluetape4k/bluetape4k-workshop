package io.bluetape4k.workshop.cache.benchmark.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.requireNotBlank
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RedissonConfig {
    companion object : KLoggingChannel()

    @Bean(destroyMethod = "shutdown")
    fun redissonClient(
        @Value("\${spring.data.redis.host:localhost}") host: String,
        @Value("\${spring.data.redis.port:6379}") port: Int,
    ): RedissonClient {
        val redisHost = host.requireNotBlank("spring.data.redis.host")
        val config = Config().apply {
            useSingleServer()
                .setAddress("redis://$redisHost:$port")
                .setConnectionPoolSize(64)
                .setConnectionMinimumIdleSize(16)
                .setTimeout(5000)
                .setRetryAttempts(3)
        }
        return Redisson.create(config)
    }
}
