package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.cache.benchmark.AbstractCacheBenchmarkTest
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.springframework.data.redis.core.RedisTemplate
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

class ReadThroughServiceTest(
    @Autowired private val service: ReadThroughService,
    @Autowired private val productRepository: ProductRepository,
    @Autowired private val redisTemplate: RedisTemplate<String, Product>,
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
    fun `findById loads from repository on cache miss and populates Redis cache`() {
        val existing = productRepository.findAll().first()
        service.evict(existing.id)

        (redisTemplate.hasKey(ReadThroughService.KEY_PREFIX + existing.id)).shouldBeFalse()

        val first = service.findById(existing.id)
        first shouldBeEqualTo existing

        (redisTemplate.hasKey(ReadThroughService.KEY_PREFIX + existing.id)).shouldBeTrue()

        val second = service.findById(existing.id)
        second shouldBeEqualTo first
    }

    @Test
    fun `save populates cache`() {
        val product = Product(name = "RTTest", category = "Test", price = BigDecimal("5.99"), stock = 4)
        val saved = service.save(product)
        (saved.id > 0L).shouldBeTrue()
        service.findById(saved.id) shouldBeEqualTo saved
    }
}
