package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.cache.benchmark.AbstractCacheBenchmarkTest
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

class WriteThroughServiceTest(
    @Autowired private val service: WriteThroughService,
    @Autowired private val productRepository: ProductRepository,
) : AbstractCacheBenchmarkTest() {

    @Test
    fun `save writes to both cache and DB synchronously`() {
        val product = Product(name = "WTTest", category = "Test", price = BigDecimal("6.99"), stock = 8)
        val saved = service.save(product)
        (saved.id > 0L).shouldBeTrue()

        // Cache hit
        service.findById(saved.id) shouldBeEqualTo saved

        // DB persisted
        productRepository.findById(saved.id).orElse(null) shouldBeEqualTo saved
    }

    @Test
    fun `findById returns cached product`() {
        val existing = productRepository.findAll().first()
        service.findById(existing.id) // prime cache
        service.findById(existing.id) shouldBeEqualTo existing
    }
}
