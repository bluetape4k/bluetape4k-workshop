package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Profile 6 — Write-Through Cache.
 *
 * Every write is applied synchronously to **both** Redis cache and the DB.
 * - Reads: cache-first, fall through to DB on miss
 * - Writes: synchronous dual-write (cache + DB) — strong consistency, higher write latency
 */
@Service
class WriteThroughService(
    private val productRepository: ProductRepository,
    private val redisTemplate: RedisTemplate<String, Product>,
) : ProductCacheService {
    companion object : KLoggingChannel() {
        const val KEY_PREFIX = "products:write-through:"
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
        // Write-through: update DB first, then update cache atomically
        val saved = productRepository.save(product)
        redisTemplate.opsForValue().set(cacheKey(saved.id), saved, TTL)
        return saved
    }

    override fun evict(id: Long) {
        redisTemplate.delete(cacheKey(id))
    }

    override fun clearAll() {
        val keys = redisTemplate.keys("$KEY_PREFIX*")
        if (!keys.isNullOrEmpty()) redisTemplate.delete(keys)
    }
}
