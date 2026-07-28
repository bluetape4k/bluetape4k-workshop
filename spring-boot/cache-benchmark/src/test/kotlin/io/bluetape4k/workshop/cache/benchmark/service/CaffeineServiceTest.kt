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

class CaffeineServiceTest(
    @Autowired private val service: CaffeineService,
    @Autowired private val productRepository: ProductRepository,
) : AbstractCacheBenchmarkTest() {

    @Test
    fun `findById returns product and caches it`() {
        val existing = productRepository.findAll().first()
        val first = service.findById(existing.id)
        val second = service.findById(existing.id)   // should come from cache
        first shouldBeEqualTo existing
        second shouldBeEqualTo first
    }

    @Test
    fun `findById returns null for unknown id`() {
        service.findById(999_999L).shouldBeNull()
    }

    @Test
    fun `save updates cache`() {
        val product = Product(name = "CafTest", category = "Test", price = BigDecimal("2.99"), stock = 3)
        val saved = service.save(product)
        (saved.id > 0L).shouldBeTrue()
        service.findById(saved.id) shouldBeEqualTo saved
    }

    @Test
    fun `evict removes entry from cache`() {
        val existing = productRepository.findAll().first()
        service.findById(existing.id)   // populate cache
        service.evict(existing.id)
        // evict 이후 다음 호출은 DB 로 가므로 여전히 record 를 반환해야 합니다.
        service.findById(existing.id) shouldBeEqualTo existing
    }
}
