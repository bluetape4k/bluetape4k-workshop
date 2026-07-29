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
 * resilient cache 설정입니다.
 *
 * 다음 component 를 설정합니다.
 * - Lettuce Redis connection factory(primary cache)
 * - Caffeine local cache(Redis circuit 이 OPEN 일 때 fallback)
 * - demo 용 fast-trip 설정을 가진 Redis call CircuitBreaker
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
     * Redis circuit breaker 가 OPEN 일 때 fallback 으로 사용하는 Caffeine local cache 입니다.
     *
     * Redis 가 복구된 뒤 stale data 가 제거되도록 짧은 TTL(30초)을 사용합니다.
     */
    @Bean(LOCAL_CACHE_NAME)
    fun localCache(): com.github.benmanes.caffeine.cache.Cache<String, String> {
        return Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build()
    }

    /**
     * Redis call 을 위한 Resilience4j CircuitBreaker 입니다.
     *
     * 빠른 demo 를 위해 5-call window 안에서 3번 실패하면 OPEN 됩니다.
     * OPEN 에서 5초 대기하고 HALF-OPEN 에서 2번의 call 로 probe 합니다.
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
