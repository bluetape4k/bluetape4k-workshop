package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.cache.benchmark.AbstractCacheBenchmarkTest
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

class NoCacheServiceTest(
    @Autowired private val service: NoCacheService,
    @Autowired private val productRepository: ProductRepository,
) : AbstractCacheBenchmarkTest() {

    @Test
    fun `findById returns product from DB`() {
        val existing = productRepository.findAll().first()
        val found = service.findById(existing.id)
        found shouldBeEqualTo existing
    }

    @Test
    fun `findById returns null for unknown id`() {
        service.findById(999_999L).shouldBeNull()
    }

    @Test
    fun `save persists product`() {
        val product = Product(name = "Test", category = "Test", price = BigDecimal("1.99"), stock = 5)
        val saved = service.save(product)
        (saved.id > 0L).shouldBeTrue()
        service.findById(saved.id) shouldBeEqualTo saved
    }
}
