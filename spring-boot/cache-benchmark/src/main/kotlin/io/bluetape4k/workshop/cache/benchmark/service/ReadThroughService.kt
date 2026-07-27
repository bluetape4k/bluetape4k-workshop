package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.workshop.cache.benchmark.service.ProductMapPersistenceContract.qualifiedCacheName
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import org.redisson.api.RMap
import org.redisson.api.RedissonClient
import org.redisson.api.options.MapOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Profile 5 — Read-Through Cache.
 *
 * Read strategy: Redisson map with [ProductMapLoader]-owned database misses.
 *
 * - On cache hit: return cached value (no DB access).
 * - On cache miss: Redisson invokes the map loader and populates the map.
 * - Writes are intentionally unsupported because this profile demonstrates
 *   read-through ownership only; use write-through or write-behind for writes.
 */
@Service
class ReadThroughService(
    redissonClient: RedissonClient,
    productMapLoader: ProductMapLoader,
    @Value("\${cache.benchmark.namespace:cache-benchmark}") namespace: String,
) : ProductCacheService {
    companion object {
        const val CACHE_NAME = "products:read-through"
    }

    private val products: RMap<Long, Product> =
        redissonClient.getMap(
            MapOptions.name<Long, Product>(qualifiedCacheName(namespace, CACHE_NAME))
                .loader(productMapLoader)
        )

    override fun findById(id: Long): Product? = products[id]

    override fun save(product: Product): Product {
        throw UnsupportedOperationException("Read-through profile supports reads only; writes need a writer-backed profile.")
    }

    override fun evict(id: Long) {
        products.fastRemove(id)
    }

    override fun clearAll() {
        products.clear()
    }
}
