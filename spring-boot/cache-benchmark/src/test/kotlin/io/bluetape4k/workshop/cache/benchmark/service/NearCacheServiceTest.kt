package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.cache.benchmark.AbstractCacheBenchmarkTest
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

class NearCacheServiceTest(
    @Autowired private val service: NearCacheService,
    @Autowired private val productRepository: ProductRepository,
): AbstractCacheBenchmarkTest() {

    companion object: KLogging()

    @Test
    fun `findById populates local and remote cache tiers`() {
        val existing = productRepository.findAll().first()
        val first = service.findById(existing.id)   // DB miss → populate both tiers
        val second = service.findById(existing.id)  // local-tier hit

        log.debug { "first=$first, second=$second" }
        first shouldBeEqualTo existing
        second shouldBeEqualTo first
    }

    @Test
    fun `findById returns null for unknown id`() {
        service.findById(999_999L).shouldBeNull()
    }

    @Test
    fun `save writes through to near cache`() {
        val product = Product(name = "NearTest", category = "Test", price = BigDecimal("4.99"), stock = 2)
        val saved = service.save(product)

        log.debug { "saved=$saved" }
        saved.id shouldBeGreaterThan 0L
        service.findById(saved.id) shouldBeEqualTo saved
    }

    @Test
    fun `evict removes entry from both cache tiers`() {
        val existing = productRepository.findAll().first()
        log.debug { "existing=$existing" }

        service.findById(existing.id)
        service.evict(existing.id)

        service.findById(existing.id) shouldBeEqualTo existing  // re-loads from DB
    }
}
