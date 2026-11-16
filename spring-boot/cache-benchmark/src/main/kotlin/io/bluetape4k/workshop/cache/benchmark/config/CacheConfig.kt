package io.bluetape4k.workshop.cache.benchmark.config

import com.github.benmanes.caffeine.cache.Caffeine
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.cache.benchmark.service.ProductMapPersistenceContract.qualifiedCacheName
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.CacheManager
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext

@Configuration
class CacheConfig {
    companion object: KLoggingChannel()

    @Bean
    @Primary
    fun caffeineCacheManager(): CacheManager {
        val manager = CaffeineCacheManager()
        manager.setCaffeine(
            Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(60.seconds())
                .recordStats()
        )
        return manager
    }

    @Bean("redisCacheManager")
    fun redisCacheManager(
        redisConnectionFactory: RedisConnectionFactory,
        @Value("\${cache.benchmark.namespace:cache-benchmark}") namespace: String,
    ): CacheManager {
        val config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(60.seconds())
            .prefixCacheNameWith("${qualifiedCacheName(namespace, "spring-cache")}:")
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    GenericJacksonJsonRedisSerializer.builder()
                        .enableUnsafeDefaultTyping()
                        .build()
                )
            )
        return RedisCacheManager.builder(redisConnectionFactory)
            .cacheDefaults(config)
            .build()
    }
}
