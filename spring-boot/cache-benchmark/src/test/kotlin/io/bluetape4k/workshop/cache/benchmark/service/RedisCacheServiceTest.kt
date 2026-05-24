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

class RedisCacheServiceTest(
    @Autowired private val service: RedisCacheService,
    @Autowired private val productRepository: ProductRepository,
) : AbstractCacheBenchmarkTest() {

    @Test
    fun `findById stores and retrieves from Redis cache`() {
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
    fun `save updates Redis cache`() {
        val product = Product(name = "RedisTest", category = "Test", price = BigDecimal("3.99"), stock = 7)
        val saved = service.save(product)
        (saved.id > 0L).shouldBeTrue()
        service.findById(saved.id) shouldBeEqualTo saved
    }
}
