package io.bluetape4k.workshop.cache.resilience.config

import com.github.benmanes.caffeine.cache.Caffeine
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration

/**
 * Resilient cache configuration.
 *
 * Sets up:
 * - Lettuce Redis connection factory (primary cache)
 * - Caffeine local cache (fallback when Redis circuit is OPEN)
 * - CircuitBreaker for Redis calls with fast-trip settings for demos
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
class ResilientCacheConfig {

    companion object : KLoggingChannel() {
        const val REDIS_CIRCUIT_BREAKER = "redis-cache"
        const val LOCAL_CACHE_NAME = "products-local"
        const val REDIS_CACHE_NAME = "products-redis"
    }

    @Value("\${spring.data.redis.host:localhost}")
    lateinit var redisHost: String

    @Value("\${spring.data.redis.port:6379}")
    var redisPort: Int = 6379

    @Bean
    fun lettuceConnectionFactory(): LettuceConnectionFactory {
        val config = RedisStandaloneConfiguration(redisHost, redisPort)
        return LettuceConnectionFactory(config)
    }

    @Bean
    fun redisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, String> {
        return RedisTemplate<String, String>().apply {
            setConnectionFactory(connectionFactory)
            keySerializer = StringRedisSerializer.UTF_8
            valueSerializer = StringRedisSerializer.UTF_8
            hashKeySerializer = StringRedisSerializer.UTF_8
            hashValueSerializer = StringRedisSerializer.UTF_8
        }
    }

    /**
     * Caffeine local cache used as fallback when the Redis circuit breaker is OPEN.
     *
     * Short TTL (30 s) so stale data is evicted once Redis recovers.
     */
    @Bean(LOCAL_CACHE_NAME)
    fun localCache(): com.github.benmanes.caffeine.cache.Cache<String, String> {
        return Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build()
    }

    /**
     * Resilience4j CircuitBreaker for Redis calls.
     *
     * Tuned for fast demo: opens after 3 failures in a 5-call window,
     * waits 5 s in OPEN, probes with 2 calls in HALF-OPEN.
     */
    @Bean
    fun redisCacheCircuitBreaker(registry: CircuitBreakerRegistry): CircuitBreaker {
        val config = CircuitBreakerConfig.custom()
            .slidingWindowSize(5)
            .minimumNumberOfCalls(3)
            .failureRateThreshold(60f)              // 60% failure → OPEN
            .waitDurationInOpenState(Duration.ofSeconds(5))
            .permittedNumberOfCallsInHalfOpenState(2)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .recordExceptions(Exception::class.java)
            .build()

        val cb = registry.circuitBreaker(REDIS_CIRCUIT_BREAKER, config)

        cb.eventPublisher
            .onStateTransition { e ->
                log.info { "[CircuitBreaker] state transition: ${e.stateTransition}" }
            }
            .onSuccess { log.info { "[CircuitBreaker] call succeeded" } }
            .onError { e -> log.info { "[CircuitBreaker] call failed: ${e.throwable.message}" } }

        return cb
    }
}
