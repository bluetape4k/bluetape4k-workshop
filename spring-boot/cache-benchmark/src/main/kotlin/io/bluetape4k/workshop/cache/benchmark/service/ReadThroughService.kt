package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Profile 5 — Read-Through Cache.
 *
 * Read strategy: Redis-backed cache with explicit miss handling.
 *
 * - On cache hit: return cached value (no DB access).
 * - On cache miss: load from DB and populate cache.
 *
 * Write strategy: cache-aside / application-managed.
 * Writes are persisted in the repository first, then reflected in Redis so this
 * profile is intentionally not a write-through strategy.
 */
@Service
class ReadThroughService(
    private val productRepository: ProductRepository,
    private val redisTemplate: RedisTemplate<String, Product>,
) : ProductCacheService {
    companion object : KLoggingChannel() {
        const val KEY_PREFIX = "products:read-through:"
        val TTL: Duration = Duration.ofSeconds(60)
    }

    private fun cacheKey(id: Long) = "$KEY_PREFIX$id"

    override fun findById(id: Long): Product? {
        val key = cacheKey(id)
        // 1. Read from cache
        val cached = redisTemplate.opsForValue().get(key)
        if (cached != null) return cached

        // 2. Cache miss: read through to DB
        return productRepository.findById(id).orElse(null)?.also { product ->
            redisTemplate.opsForValue().set(key, product, TTL)
        }
    }

    override fun save(product: Product): Product {
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
