package io.bluetape4k.workshop.cache.redis.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.redis.spring.serializer.RedisBinarySerializers
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration

@Configuration
@EnableCaching
class LettuceRedisCacheConfiguration {

    companion object: KLoggingChannel()

    @Value("\${spring.data.redis.host}")
    lateinit var redisHost: String

    @Value("\${spring.data.redis.port:6379}")
    var redisPort: Int = 6379

    @Bean
    fun cacheManager(connectionFactory: RedisConnectionFactory): CacheManager {
        return RedisCacheManager.builder(connectionFactory)
            .transactionAware()
            .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofDays(1)))
            .build()
    }

    @Bean
    fun lettuceConnectionFactory(applicationTaskExecutor: AsyncTaskExecutor): LettuceConnectionFactory {
        val configuration = RedisStandaloneConfiguration(redisHost, redisPort)

        // Lettuce 작업을 Virtual Threads로 실행하기 위해 TaskExecutor를 설정 (see AsyncConfig)
        return LettuceConnectionFactory(configuration).apply {
            setExecutor(applicationTaskExecutor)
        }
    }

    @Bean
    @ConditionalOnMissingBean(name = ["redisTemplate"])
    fun redisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<Any, Any> {
        return RedisTemplate<Any, Any>().apply {
            setConnectionFactory(connectionFactory)
            setDefaultSerializer(RedisBinarySerializers.LZ4Fury)
            keySerializer = StringRedisSerializer.UTF_8
            valueSerializer = RedisBinarySerializers.LZ4Fury
        }
    }
}
