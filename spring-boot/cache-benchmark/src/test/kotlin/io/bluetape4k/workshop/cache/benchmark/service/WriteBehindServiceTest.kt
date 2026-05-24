package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.cache.benchmark.AbstractCacheBenchmarkTest
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Duration

class WriteBehindServiceTest(
    @Autowired private val service: WriteBehindService,
    @Autowired private val productRepository: ProductRepository,
): AbstractCacheBenchmarkTest() {

    @Test
    fun `save new entity persists to DB and populates cache`() {
        val product = Product(name = "WBNew", category = "Test", price = BigDecimal("7.99"), stock = 6)
        val saved = service.save(product)

        // New entity: ID must be DB-assigned (> 0)
        (saved.id > 0L).shouldBeTrue()
        // Cache should hold the value immediately
        service.findById(saved.id) shouldBeEqualTo saved
        // DB must already have the record (synchronous save for new entities)
        productRepository.findById(saved.id).orElse(null) shouldBeEqualTo saved
    }

    @Test
    fun `save update writes cache immediately and flushes DB asynchronously`() {
        val existing = productRepository.findAll().first()
        val updated = existing.copy(name = "WBUpdated-${System.nanoTime()}")

        service.save(updated)

        // Cache must be updated immediately (synchronous write)
        service.findById(existing.id) shouldBeEqualTo updated

        // DB flush is async — poll up to 5 s for the write-behind flush to complete
        await atMost Duration.ofSeconds(5) until {
            productRepository.findById(existing.id).orElse(null)?.name == updated.name
        }
    }

    @Test
    fun `findById returns cached product on second access`() {
        val existing = productRepository.findAll().first()
        service.findById(existing.id)                     // prime cache
        service.findById(existing.id) shouldBeEqualTo existing
    }
}
