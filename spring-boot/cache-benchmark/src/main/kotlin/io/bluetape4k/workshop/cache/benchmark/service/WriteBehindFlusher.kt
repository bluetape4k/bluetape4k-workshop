package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/**
 * Async DB flusher for the write-behind cache profile.
 *
 * **Must be a separate @Component** (not a method on [WriteBehindService]) so that
 * the `@Async` annotation is honoured through Spring's proxy mechanism.
 * Calling `@Async` methods on `this` bypasses the proxy and executes synchronously.
 */
@Component
class WriteBehindFlusher(private val productRepository: ProductRepository) {
    companion object : KLoggingChannel()

    /**
     * Persists [product] to the database asynchronously.
     *
     * Failures are logged and swallowed — the cache already holds the latest value.
     */
    @Async
    fun persist(product: Product) {
        try {
            productRepository.save(product)
        } catch (e: Exception) {
            log.warn(e) { "Write-behind DB flush failed for product id=${product.id}" }
        }
    }
}
