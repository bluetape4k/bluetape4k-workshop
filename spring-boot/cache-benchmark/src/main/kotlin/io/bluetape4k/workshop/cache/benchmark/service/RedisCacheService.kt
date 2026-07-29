package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * Profile 3 — Redis Distributed Cache 입니다.
 *
 * Redis 기반 [CacheManager](Spring Data Redis)와 Spring `@Cacheable` 을 사용합니다.
 * cache entry 는 모든 JVM instance 사이에 공유되며 TTL 은 60초입니다.
 */
@Service
class RedisCacheService(private val productRepository: ProductRepository) : ProductCacheService {
    companion object : KLoggingChannel() {
        const val CACHE_NAME = "products-redis"
    }

    @Cacheable(cacheNames = [CACHE_NAME], key = "#id", cacheManager = "redisCacheManager")
    override fun findById(id: Long): Product? = productRepository.findById(id).orElse(null)

    @CachePut(cacheNames = [CACHE_NAME], key = "#result.id", cacheManager = "redisCacheManager")
    override fun save(product: Product): Product = productRepository.save(product)

    @CacheEvict(cacheNames = [CACHE_NAME], key = "#id", cacheManager = "redisCacheManager")
    override fun evict(id: Long) {}

    @CacheEvict(cacheNames = [CACHE_NAME], allEntries = true, cacheManager = "redisCacheManager")
    override fun clearAll() {}
}
