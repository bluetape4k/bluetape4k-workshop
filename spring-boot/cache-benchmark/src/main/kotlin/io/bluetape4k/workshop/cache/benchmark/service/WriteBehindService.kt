package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Profile 7 — Write-Behind Cache.
 *
 * Writes are applied immediately to the Redis cache and asynchronously to the DB.
 * - Reads: cache-first, fall through to DB on miss
 * - Writes: fast (cache only, synchronous) + async DB flush via [@Async]
 *
 * Trade-off: lower write latency at the cost of eventual consistency.
 */
@Service
class WriteBehindService(
    private val productRepository: ProductRepository,
    private val redisTemplate: RedisTemplate<String, Product>,
) : ProductCacheService {
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
        // Write-behind: update cache immediately, schedule async DB write
        redisTemplate.opsForValue().set(cacheKey(product.id), product, TTL)
        asyncPersist(product)
        return product
    }

    @Async
    fun asyncPersist(product: Product) {
        try {
            productRepository.save(product)
        } catch (e: Exception) {
            log.warn(e) { "Write-behind DB flush failed for product id=${product.id}" }
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
