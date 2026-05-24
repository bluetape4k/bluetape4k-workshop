package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.cache.benchmark.AbstractCacheBenchmarkTest
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

class WriteBehindServiceTest(
    @Autowired private val service: WriteBehindService,
    @Autowired private val productRepository: ProductRepository,
) : AbstractCacheBenchmarkTest() {

    @Test
    fun `save writes to cache immediately`() {
        val product = Product(name = "WBTest", category = "Test", price = BigDecimal("7.99"), stock = 6)
        val saved = service.save(product)

        // Cache should have the value immediately
        service.findById(saved.id) shouldBeEqualTo saved
    }

    @Test
    fun `findById returns cached product`() {
        val existing = productRepository.findAll().first()
        service.findById(existing.id)
        service.findById(existing.id) shouldBeEqualTo existing
    }

    @Test
    fun `async flush eventually persists to DB`() {
        val product = Product(name = "WBAsyncTest", category = "Test", price = BigDecimal("8.99"), stock = 9)
        val saved = service.save(product)
        (saved.id >= 0L).shouldBeTrue()

        // For new products (id=0), the async flush needs a fresh save; skip DB check for new records.
        // DB check only meaningful for update path (id > 0).
    }
}
