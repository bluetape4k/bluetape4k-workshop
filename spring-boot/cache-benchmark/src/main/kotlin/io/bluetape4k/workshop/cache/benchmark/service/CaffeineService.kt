package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * Profile 2 — Caffeine Local Cache 입니다.
 *
 * Caffeine 기반 [CacheManager] 와 Spring `@Cacheable` 을 사용합니다.
 * cache 는 local(in-JVM)이므로 read 는 매우 빠르지만 instance 사이에 공유되지 않습니다.
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
