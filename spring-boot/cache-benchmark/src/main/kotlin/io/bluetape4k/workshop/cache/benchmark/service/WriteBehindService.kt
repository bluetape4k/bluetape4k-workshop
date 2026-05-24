package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Profile 7 — Write-Behind Cache.
 *
 * Writes are applied immediately to the Redis cache and asynchronously to the DB.
 * - Reads: cache-first, fall through to DB on miss
 * - Writes (new entity): DB save first to get the generated ID, then populate cache
 * - Writes (update): cache updated immediately, DB flush deferred via [WriteBehindFlusher]
 *
 * ## Why a separate flusher?
 * Spring's `@Async` requires the call to pass through a Spring proxy.
 * Internal `this`-calls bypass the proxy and execute synchronously.
 * [WriteBehindFlusher] is a separate `@Component` so the async invocation
 * goes through the proxy correctly.
 *
 * Trade-off: lower write latency for updates at the cost of eventual consistency.
 */
@Service
class WriteBehindService(
    private val productRepository: ProductRepository,
    private val redisTemplate: RedisTemplate<String, Product>,
    private val flusher: WriteBehindFlusher,
): ProductCacheService {
    companion object : KLoggingChannel() {
        const val KEY_PREFIX = "products:write-behind:"
        val TTL: Duration = Duration.ofSeconds(60)
    }

    private fun cacheKey(id: Long) = "$KEY_PREFIX$id"

    override fun findById(id: Long): Product? {
        val key = cacheKey(id)
        val cached = redisTemplate.opsForValue().get(key)
        if (cached != null) return cached

        return productRepository.findById(id).orElse(null)?.also { product ->
            redisTemplate.opsForValue().set(key, product, TTL)
        }
    }

    override fun save(product: Product): Product {
        return if (product.id == 0L) {
            // New entity: must persist to DB first to obtain the generated ID
            val saved = productRepository.save(product)
            redisTemplate.opsForValue().set(cacheKey(saved.id), saved, TTL)
            saved
        } else {
            // Existing entity (update): write-behind — update cache immediately,
            // flush to DB asynchronously via proxy-based @Async flusher
            redisTemplate.opsForValue().set(cacheKey(product.id), product, TTL)
            flusher.persist(product)
            product
        }
    }

    override fun evict(id: Long) {
        redisTemplate.delete(cacheKey(id))
    }

    override fun clearAll() {
        val keys = redisTemplate.keys("$KEY_PREFIX*")
        if (!keys.isNullOrEmpty()) redisTemplate.delete(keys)
    }
}
