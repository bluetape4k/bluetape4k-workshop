package io.bluetape4k.workshop.cache.resilience.service

import com.github.benmanes.caffeine.cache.Cache
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.resilience4j.SuspendDecorators
import io.bluetape4k.workshop.cache.resilience.config.ResilientCacheConfig
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Redis primary cache 와 Caffeine fallback 을 사용하는 product service 입니다.
 *
 * ## Cache strategy
 * - GET: CircuitBreaker 가 Redis read 를 감쌉니다. OPEN 상태에서는 Caffeine 으로 fallback 합니다.
 * - PUT: Redis 와 Caffeine 양쪽에 write 합니다. best-effort 이며 Redis write failure 는 throw 하지 않고 log 합니다.
 * - EVICT: 두 store 에서 모두 제거합니다.
 *
 * ## Circuit breaker states
 * - **CLOSED** — 모든 read 가 Redis 로 갑니다.
 * - **OPEN** — read 가 Caffeine 으로 fallback 합니다(stale-while-revalidate).
 * - **HALF-OPEN** — probe call 이 Redis 를 시도합니다. 성공하면 CLOSED, 실패하면 OPEN 입니다.
 */
@Service
class ResilientProductService(
    private val redisTemplate: RedisTemplate<String, String>,
    @Qualifier(ResilientCacheConfig.LOCAL_CACHE_NAME)
    private val localCache: Cache<String, String>,
    private val circuitBreaker: CircuitBreaker,
) {
    companion object : KLoggingChannel() {
        private const val REDIS_KEY_PREFIX = "product:"
    }

    /**
     * ID 로 product 를 조회합니다.
     *
     * CircuitBreaker 를 통해 Redis 를 시도하고, circuit 이 OPEN 이면 Caffeine 으로 fallback 합니다.
     * 어느 cache 에도 값이 없으면 `null` 을 반환합니다.
     */
    suspend fun getProduct(id: String): String? {
        val redisKey = "$REDIS_KEY_PREFIX$id"

        val result = SuspendDecorators.ofSupplier {
            log.debug { "Fetching product[$id] from Redis" }
            redisTemplate.opsForValue().get(redisKey)
        }
            .withCircuitBreaker(circuitBreaker)
            .withFallback { ex ->
                log.warn { "Redis fallback triggered for product[$id]: ${ex?.message}" }
                localCache.getIfPresent(id)
            }
            .invoke()

        if (result != null) {
            log.debug { "Cache hit for product[$id] (state=${circuitBreaker.state})" }
        } else {
            log.debug { "Cache miss for product[$id]" }
        }

        return result
    }

    /**
     * product 를 Redis 와 Caffeine 양쪽에 저장합니다.
     *
     * Redis write failure 는 catch 해서 log 합니다. local cache 는 항상 갱신하므로
     * 최소한 in-process replica 는 일관성을 유지합니다.
     */
    suspend fun putProduct(id: String, value: String) {
        val redisKey = "$REDIS_KEY_PREFIX$id"

        // local cache 를 항상 먼저 갱신하고 best-effort Redis write 를 이어서 수행합니다.
        localCache.put(id, value)
        log.debug { "Stored product[$id] in local cache" }

        try {
            redisTemplate.opsForValue().set(redisKey, value, Duration.ofMinutes(5))
            log.info { "Stored product[$id] in Redis" }
        } catch (ex: Exception) {
            log.warn(ex) { "Redis write failed for product[$id]; local cache updated only" }
        }
    }

    /**
     * Redis 와 local cache 양쪽에서 product 를 evict 합니다.
     */
    suspend fun evictProduct(id: String) {
        val redisKey = "$REDIS_KEY_PREFIX$id"
        localCache.invalidate(id)
        try {
            redisTemplate.delete(redisKey)
            log.info { "Evicted product[$id] from Redis and local cache" }
        } catch (ex: Exception) {
            log.warn(ex) { "Redis eviction failed for product[$id]; local cache cleared only" }
        }
    }

    /** observability endpoint 에서 사용할 현재 circuit breaker state 를 반환합니다. */
    fun circuitBreakerState(): CircuitBreaker.State = circuitBreaker.state

    /** circuit breaker metrics snapshot 을 반환합니다. */
    fun circuitBreakerMetrics(): CircuitBreaker.Metrics = circuitBreaker.metrics
}
