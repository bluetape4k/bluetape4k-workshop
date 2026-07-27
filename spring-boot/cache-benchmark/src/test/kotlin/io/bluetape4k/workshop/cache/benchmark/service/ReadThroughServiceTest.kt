package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.workshop.cache.benchmark.AbstractCacheBenchmarkTest
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

class ReadThroughServiceTest(
    @Autowired private val service: ReadThroughService,
    @Autowired private val productRepository: ProductRepository,
) : AbstractCacheBenchmarkTest() {

    @Test
    fun `findById reads through to DB on first access`() {
        val existing = productRepository.findAll().first()
        val result = service.findById(existing.id)
        result shouldBeEqualTo existing
    }

    @Test
    fun `findById returns cached value on second access`() {
        val existing = productRepository.findAll().first()
        val first = service.findById(existing.id)
        val second = service.findById(existing.id)
        first shouldBeEqualTo existing
        second shouldBeEqualTo first
    }

    @Test
    fun `findById returns null for unknown id`() {
        service.findById(999_999L).shouldBeNull()
    }

    @Test
    fun `findById cache hit is served from Redisson map after loader miss`() {
        val existing =
            productRepository.save(Product(name = "RTLoaded", category = "Test", price = BigDecimal("5.99"), stock = 4))

        service.evict(existing.id)
        service.findById(existing.id) shouldBeEqualTo existing

        productRepository.deleteById(existing.id)
        service.findById(existing.id) shouldBeEqualTo existing
    }

    @Test
    fun `save is unsupported because read through profile is read only`() {
        val product = Product(name = "RTTest", category = "Test", price = BigDecimal("5.99"), stock = 4)

        assertFailsWith<UnsupportedOperationException> {
            service.save(product)
        }
    }
}
