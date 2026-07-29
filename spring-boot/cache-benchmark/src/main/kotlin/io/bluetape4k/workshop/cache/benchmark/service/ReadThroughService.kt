package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.workshop.cache.benchmark.service.ProductMapPersistenceContract.qualifiedCacheName
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import org.redisson.api.RMap
import org.redisson.api.RedissonClient
import org.redisson.api.options.MapOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Profile 5 — Read-Through Cache 입니다.
 *
 * Read strategy 는 database miss 를 [ProductMapLoader] 가 소유하는 Redisson map 입니다.
 *
 * - cache hit: cached value 를 반환합니다(DB 접근 없음).
 * - cache miss: Redisson 이 map loader 를 호출하고 map 을 채웁니다.
 * - 이 profile 은 read-through ownership 만 보여주므로 write 는 의도적으로 지원하지 않습니다.
 *   write 에는 write-through 또는 write-behind 를 사용합니다.
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
