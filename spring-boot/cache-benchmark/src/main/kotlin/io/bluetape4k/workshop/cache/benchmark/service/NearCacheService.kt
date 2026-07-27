package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.cache.benchmark.service.ProductMapPersistenceContract.qualifiedCacheName
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.redisson.api.RLocalCachedMap
import org.redisson.api.RedissonClient
import org.redisson.api.options.LocalCachedMapOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Profile 4 — Redisson Near Cache (RLocalCachedMap).
 *
 * Uses Redisson's [RLocalCachedMap] which provides a 2-tier cache:
 * - Tier 1: Caffeine-backed local (in-JVM) map — ultra-low latency
 * - Tier 2: Redis backing store — shared across instances
 *
 * This is the bluetape4k-recommended pattern for distributed near caching.
 * See: [io.bluetape4k.cache.nearcache.NearCacheOperations]
 */
@Service
class NearCacheService(
    private val productRepository: ProductRepository,
    redissonClient: RedissonClient,
    @Value("\${cache.benchmark.namespace:cache-benchmark}") namespace: String,
) : ProductCacheService {
    companion object : KLoggingChannel() {
        const val CACHE_NAME = "products-near-cache"
    }

    private val nearCache: RLocalCachedMap<Long, Product> = run {
        val options = LocalCachedMapOptions.name<Long, Product>(qualifiedCacheName(namespace, CACHE_NAME))
            .cacheSize(10_000)
            .evictionPolicy(LocalCachedMapOptions.EvictionPolicy.LRU)
            .maxIdle(Duration.ofSeconds(60))
            .timeToLive(Duration.ofSeconds(300))
            .syncStrategy(LocalCachedMapOptions.SyncStrategy.INVALIDATE)
        redissonClient.getLocalCachedMap(options)
    }

    override fun findById(id: Long): Product? {
        // 1. Check local (in-JVM) tier
        return nearCache.getOrDefault(id, null)
            ?: run {
                // 2. Cache miss: load from DB and populate near cache
                productRepository.findById(id).orElse(null)?.also { product ->
                    nearCache[id] = product
                }
            }
    }

    override fun save(product: Product): Product {
        val saved = productRepository.save(product)
        nearCache[saved.id] = saved          // write-through to near cache
        return saved
    }

    override fun evict(id: Long) {
        nearCache.remove(id)
    }

    override fun clearAll() {
        nearCache.clearLocalCache()
        nearCache.clear()
    }
}
