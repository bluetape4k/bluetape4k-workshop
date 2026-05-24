package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.springframework.stereotype.Service

/**
 * Profile 1 — No Cache (Baseline).
 *
 * Every [findById] call hits the database directly.
 * This is the baseline for measuring the overhead that caching eliminates.
 */
@Service
class NoCacheService(private val productRepository: ProductRepository) : ProductCacheService {
    companion object : KLoggingChannel()

    override fun findById(id: Long): Product? = productRepository.findById(id).orElse(null)

    override fun save(product: Product): Product = productRepository.save(product)

    override fun evict(id: Long) {
        // No cache to evict — no-op
    }

    override fun clearAll() {
        // No cache to clear — no-op
    }
}
