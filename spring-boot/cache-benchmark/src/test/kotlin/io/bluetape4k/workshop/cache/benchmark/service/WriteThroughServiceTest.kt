package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
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
    fun `save updates existing product through Redisson writer before returning`() {
        val existing = productRepository.findAll().first()
        val updated = existing.copy(name = "WTUpdated-${System.nanoTime()}", price = BigDecimal("6.99"), stock = 8)

        val saved = service.save(updated)

        saved shouldBeEqualTo updated
        service.findById(existing.id) shouldBeEqualTo updated
        productRepository.findById(existing.id).orElse(null) shouldBeEqualTo updated
    }

    @Test
    fun `save rejects generated id inserts because map writer needs a stable key`() {
        val product = Product(name = "WTNew", category = "Test", price = BigDecimal("6.99"), stock = 8)

        assertFailsWith<IllegalArgumentException> {
            service.save(product)
        }
    }

    @Test
    fun `findById returns cached product`() {
        val existing = productRepository.findAll().first()
        service.findById(existing.id) // prime cache
        service.findById(existing.id) shouldBeEqualTo existing
    }
}
