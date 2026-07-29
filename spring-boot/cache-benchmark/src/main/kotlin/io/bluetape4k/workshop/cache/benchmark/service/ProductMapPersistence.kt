package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.redisson.api.map.MapLoader
import org.redisson.api.map.MapWriter
import org.springframework.stereotype.Component
import java.time.Duration

object ProductMapPersistenceContract {
    const val WRITE_RETRY_ATTEMPTS = 3
    const val WRITE_BEHIND_BATCH_SIZE = 50
    const val WRITE_BEHIND_DELAY_MILLIS = 1_000
    val WRITE_RETRY_INTERVAL: Duration = Duration.ofSeconds(1)

    fun requireExistingProduct(product: Product): Product {
        product.id.requirePositiveNumber("product.id")
        return product
    }

    fun qualifiedCacheName(namespace: String, cacheName: String): String =
        namespace.requireNotBlank("cache.benchmark.namespace").trim(':') + ":" + cacheName
}

@Component
class ProductMapLoader(
    private val productRepository: ProductRepository,
) : MapLoader<Long, Product> {
    companion object : KLoggingChannel()

    override fun load(key: Long): Product? {
        log.debug { "Loading product through Redisson MapLoader. id=$key" }
        return productRepository.findById(key).orElse(null)
    }

    override fun loadAllKeys(): Iterable<Long> =
        productRepository.findAll().map { product -> product.id }
}

@Component
class ProductMapWriter(
    private val productRepository: ProductRepository,
) : MapWriter<Long, Product> {
    companion object : KLoggingChannel()

    override fun write(map: Map<Long, Product>) {
        if (map.isEmpty()) return

        val products =
            map.map { (id, product) ->
                ProductMapPersistenceContract.requireExistingProduct(product.copy(id = id))
            }
        log.debug { "Persisting ${products.size} products through Redisson MapWriter." }
        productRepository.saveAll(products)
    }

    override fun delete(keys: Collection<Long>) {
        if (keys.isEmpty()) return

        log.debug { "Deleting ${keys.size} products through Redisson MapWriter." }
        productRepository.deleteAllById(keys)
    }
}
