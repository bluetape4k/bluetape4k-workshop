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
 * Product service with Redis primary cache and Caffeine fallback.
 *
 * ## Cache strategy:
 * - GET: CircuitBreaker wraps the Redis read. On OPEN, falls back to Caffeine.
 * - PUT: Writes to both Redis and Caffeine; best-effort (Redis write failure is logged, not thrown).
 * - EVICT: Removes from both stores.
 *
 * ## Circuit breaker states:
 * - **CLOSED** — all reads go to Redis.
 * - **OPEN** — reads fall back to Caffeine (stale-while-revalidate).
 * - **HALF-OPEN** — probe calls attempt Redis; success → CLOSED, failure → OPEN.
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
     * Retrieves a product by ID.
     *
     * Attempts Redis via CircuitBreaker; falls back to Caffeine when the circuit is OPEN.
     * Returns `null` when neither cache holds the value.
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
     * Stores a product in both Redis and Caffeine.
     *
     * Redis write failures are caught and logged; the local cache is always updated
     * so at least the in-process replica stays consistent.
     */
    suspend fun putProduct(id: String, value: String) {
        val redisKey = "$REDIS_KEY_PREFIX$id"

        // Always update local cache first — best-effort Redis write follows
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
     * Evicts a product from both Redis and the local cache.
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

    /** Returns the current circuit breaker state for observability endpoints. */
    fun circuitBreakerState(): CircuitBreaker.State = circuitBreaker.state

    /** Returns circuit breaker metrics snapshot. */
    fun circuitBreakerMetrics(): CircuitBreaker.Metrics = circuitBreaker.metrics
}
