package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.springframework.stereotype.Service

/**
 * Profile 1 — No Cache(Baseline) 입니다.
 *
 * 모든 [findById] 호출이 database 에 직접 접근합니다.
 * caching 이 제거하는 overhead 를 측정하기 위한 baseline 입니다.
 */
@Service
class NoCacheService(private val productRepository: ProductRepository) : ProductCacheService {
    companion object : KLoggingChannel()

    override fun findById(id: Long): Product? = productRepository.findById(id).orElse(null)

    override fun save(product: Product): Product = productRepository.save(product)

    override fun evict(id: Long) {
        // evict 할 cache 가 없으므로 no-op 입니다.
    }

    override fun clearAll() {
        // clear 할 cache 가 없으므로 no-op 입니다.
    }
}
