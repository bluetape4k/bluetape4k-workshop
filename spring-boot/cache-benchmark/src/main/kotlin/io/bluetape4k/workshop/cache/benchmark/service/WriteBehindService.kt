package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.workshop.cache.benchmark.service.ProductMapPersistenceContract.WRITE_BEHIND_BATCH_SIZE
import io.bluetape4k.workshop.cache.benchmark.service.ProductMapPersistenceContract.WRITE_BEHIND_DELAY_MILLIS
import io.bluetape4k.workshop.cache.benchmark.service.ProductMapPersistenceContract.WRITE_RETRY_ATTEMPTS
import io.bluetape4k.workshop.cache.benchmark.service.ProductMapPersistenceContract.WRITE_RETRY_INTERVAL
import io.bluetape4k.workshop.cache.benchmark.service.ProductMapPersistenceContract.qualifiedCacheName
import io.bluetape4k.workshop.cache.benchmark.service.ProductMapPersistenceContract.requireExistingProduct
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import org.redisson.api.RMap
import org.redisson.api.RedissonClient
import org.redisson.api.map.WriteMode
import org.redisson.api.options.MapOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Profile 7 — Write-Behind Cache 입니다.
 *
 * Strategy 는 Redisson-managed write-behind 입니다.
 *
 * - read: cache-first 로 읽고, miss 시 [ProductMapLoader] 를 사용합니다.
 * - write: caller 가 map 에 쓰면 Redisson 이 [ProductMapWriter] persistence 를 queue 에 넣고 batch 처리합니다.
 * - 반환값은 cache 가 update 를 수락했다는 뜻이며, DB 가 queued write 를 이미 drain 했다는 뜻은 아닙니다.
 */
@Service
class WriteBehindService(
    redissonClient: RedissonClient,
    productMapLoader: ProductMapLoader,
    productMapWriter: ProductMapWriter,
    @Value("\${cache.benchmark.namespace:cache-benchmark}") namespace: String,
) : ProductCacheService {
    companion object {
        const val CACHE_NAME = "products:write-behind"
    }

    private val mapName = qualifiedCacheName(namespace, CACHE_NAME)

    private val products: RMap<Long, Product> =
        redissonClient.getMap(
            MapOptions.name<Long, Product>(mapName)
                .loader(productMapLoader)
                .writer(productMapWriter)
                .writeMode(WriteMode.WRITE_BEHIND)
                .writeBehindBatchSize(WRITE_BEHIND_BATCH_SIZE)
                .writeBehindDelay(WRITE_BEHIND_DELAY_MILLIS)
                .writeRetryAttempts(WRITE_RETRY_ATTEMPTS)
                .writeRetryInterval(WRITE_RETRY_INTERVAL)
        )

    private val cacheOnlyProducts: RMap<Long, Product> = redissonClient.getMap(mapName)

    override fun findById(id: Long): Product? = products[id]

    override fun save(product: Product): Product {
        val existingProduct = requireExistingProduct(product)
        products[existingProduct.id] = existingProduct
        return existingProduct
    }

    override fun evict(id: Long) {
        cacheOnlyProducts.fastRemove(id)
    }

    override fun clearAll() {
        cacheOnlyProducts.clear()
    }
}
