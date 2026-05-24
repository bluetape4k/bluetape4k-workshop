package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * Profile 2 — Caffeine Local Cache.
 *
 * Uses Spring's `@Cacheable` with a Caffeine-backed [CacheManager].
 * Cache is local (in-JVM), so reads are extremely fast but not shared across instances.
 */
@Service
class CaffeineService(private val productRepository: ProductRepository) : ProductCacheService {
    companion object : KLoggingChannel() {
        const val CACHE_NAME = "products-caffeine"
    }

    @Cacheable(cacheNames = [CACHE_NAME], key = "#id")
    override fun findById(id: Long): Product? = productRepository.findById(id).orElse(null)

    @CachePut(cacheNames = [CACHE_NAME], key = "#result.id")
    override fun save(product: Product): Product = productRepository.save(product)

    @CacheEvict(cacheNames = [CACHE_NAME], key = "#id")
    override fun evict(id: Long) {}

    @CacheEvict(cacheNames = [CACHE_NAME], allEntries = true)
    override fun clearAll() {}
}
