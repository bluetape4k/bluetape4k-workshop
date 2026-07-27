package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.cache.benchmark.AbstractCacheBenchmarkTest
import io.bluetape4k.workshop.cache.benchmark.domain.Product
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Duration

class WriteBehindServiceTest(
    @Autowired private val service: WriteBehindService,
    @Autowired private val productRepository: ProductRepository,
) : AbstractCacheBenchmarkTest() {

    @Test
    fun `save rejects generated id inserts because write behind enqueue needs a stable key`() {
        val product = Product(name = "WBNew", category = "Test", price = BigDecimal("7.99"), stock = 6)

        assertFailsWith<IllegalArgumentException> {
            service.save(product)
        }
    }

    @Test
    fun `save update writes cache immediately and Redisson flushes DB asynchronously`() {
        val existing = productRepository.findAll().first()
        service.evict(existing.id)
        service.findById(existing.id) shouldBeEqualTo existing

        val updated = existing.copy(name = "WBUpdated-${System.nanoTime()}")

        service.save(updated)

        service.findById(existing.id) shouldBeEqualTo updated
        productRepository.findById(existing.id).orElse(null)?.name shouldBeEqualTo existing.name

        await atMost Duration.ofSeconds(5) untilAsserted {
            productRepository.findById(existing.id).orElse(null)?.name shouldBeEqualTo updated.name
        }
    }

    @Test
    fun `findById returns cached product on second access`() {
        val existing = productRepository.findAll().first()
        service.findById(existing.id)                     // prime cache
        service.findById(existing.id) shouldBeEqualTo existing
    }
}
