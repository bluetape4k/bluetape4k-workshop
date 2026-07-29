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
 * Profile 4 — Redisson Near Cache(RLocalCachedMap) 입니다.
 *
 * 2-tier cache 를 제공하는 Redisson [RLocalCachedMap] 을 사용합니다.
 * - Tier 1: Caffeine 기반 local(in-JVM) map — ultra-low latency
 * - Tier 2: Redis backing store — instance 사이에 공유
 *
 * distributed near caching 에 대해 bluetape4k 가 권장하는 pattern 입니다.
 * 참고: [io.bluetape4k.cache.nearcache.NearCacheOperations]
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
        // 1. local(in-JVM) tier 를 확인합니다.
        return nearCache.getOrDefault(id, null)
            ?: run {
                // 2. Cache miss: DB 에서 읽고 near cache 를 채웁니다.
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
