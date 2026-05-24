package io.bluetape4k.workshop.multitenant

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.multitenant.domain.InvoiceRecord
import io.bluetape4k.workshop.multitenant.domain.InvoiceStatus
import io.bluetape4k.workshop.multitenant.domain.TenantId
import io.bluetape4k.workshop.multitenant.service.TenantInvoiceCache
import io.bluetape4k.workshop.multitenant.service.TenantInvoiceService
import io.bluetape4k.workshop.multitenant.service.TenantKeyFactory
import io.bluetape4k.workshop.multitenant.service.TenantLockRegistry
import io.bluetape4k.workshop.multitenant.service.TenantMetrics
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.math.BigDecimal

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantIsolationTest {

    @Autowired
    private lateinit var service: TenantInvoiceService

    @Autowired
    private lateinit var cache: TenantInvoiceCache

    @Autowired
    private lateinit var keyFactory: TenantKeyFactory

    @Autowired
    private lateinit var lockRegistry: TenantLockRegistry

    @Autowired
    private lateinit var metrics: TenantMetrics

    @BeforeEach
    fun reset() {
        service.resetWorkshopState()
    }

    @Test
    fun `baseline repository and cache leak when tenant scope is omitted`() {
        val alphaInvoice = service.createInvoice(invoice(TenantId.ALPHA, "Alpha Hospital"))

        val leaked = service.unsafeFindInvoiceForBaseline(TenantId.BETA, alphaInvoice.id)

        leaked?.tenantId shouldBeEqualTo TenantId.ALPHA
        cache.keys().any { it == keyFactory.unsafeInvoiceCacheKey(alphaInvoice.id) }.shouldBeTrue()
    }

    @Test
    fun `tenant scoped repository blocks cross tenant reads and writes`() {
        val alphaInvoice = service.createInvoice(invoice(TenantId.ALPHA, "Alpha Hospital"))
        service.createInvoice(invoice(TenantId.BETA, "Beta Clinic"))

        service.findInvoice(TenantId.ALPHA, alphaInvoice.id)?.status shouldBeEqualTo InvoiceStatus.OPEN
        service.findInvoice(TenantId.BETA, alphaInvoice.id).shouldBeNull()
        service.markPaid(TenantId.BETA, alphaInvoice.id).shouldBeFalse()
        service.markPaid(TenantId.ALPHA, alphaInvoice.id).shouldBeTrue()

        val alphaAfterWrite = service.findInvoice(TenantId.ALPHA, alphaInvoice.id)
        alphaAfterWrite?.status shouldBeEqualTo InvoiceStatus.PAID
    }

    @Test
    fun `tenant scoped cache keeps same invoice id isolated by tenant key`() {
        val alphaInvoice = service.createInvoice(invoice(TenantId.ALPHA, "Alpha Hospital"))

        service.findInvoice(TenantId.ALPHA, alphaInvoice.id)?.id shouldBeEqualTo alphaInvoice.id
        service.findInvoice(TenantId.BETA, alphaInvoice.id).shouldBeNull()

        cache.keys().any {
            it == keyFactory.invoiceCacheKey(TenantId.ALPHA, alphaInvoice.id)
        }.shouldBeTrue()
        cache.keys().any {
            it == keyFactory.invoiceCacheKey(TenantId.BETA, alphaInvoice.id)
        }.shouldBeFalse()
    }

    @Test
    fun `tenant scoped lock and rate limit keys are isolated`() {
        val alphaInvoice = service.createInvoice(invoice(TenantId.ALPHA, "Alpha Hospital"))

        val alphaLockKey = service.withInvoiceLock(TenantId.ALPHA, alphaInvoice.id) { it }
        val betaLockKey = service.withInvoiceLock(TenantId.BETA, alphaInvoice.id) { it }

        alphaLockKey shouldBeEqualTo keyFactory.invoiceLockKey(TenantId.ALPHA, alphaInvoice.id)
        betaLockKey shouldBeEqualTo keyFactory.invoiceLockKey(TenantId.BETA, alphaInvoice.id)
        lockRegistry.keys().size shouldBeEqualTo 2

        val alphaFirst = service.tryRateLimit(TenantId.ALPHA, "reader", limit = 1)
        val alphaSecond = service.tryRateLimit(TenantId.ALPHA, "reader", limit = 1)
        val betaFirst = service.tryRateLimit(TenantId.BETA, "reader", limit = 1)

        alphaFirst.allowed.shouldBeTrue()
        alphaSecond.allowed.shouldBeFalse()
        betaFirst.allowed.shouldBeTrue()
        alphaFirst.key shouldBeEqualTo keyFactory.rateLimitKey(TenantId.ALPHA, "reader")
        betaFirst.key shouldBeEqualTo keyFactory.rateLimitKey(TenantId.BETA, "reader")
    }

    @Test
    fun `tenant metrics include tenant tag`() {
        val alphaInvoice = service.createInvoice(invoice(TenantId.ALPHA, "Alpha Hospital"))

        service.findInvoice(TenantId.ALPHA, alphaInvoice.id)

        metrics.invoiceReads(TenantId.ALPHA) shouldBeGreaterThan 0.0
    }

    private fun invoice(tenantId: TenantId, customerName: String): InvoiceRecord =
        InvoiceRecord(
            tenantId = tenantId,
            customerName = customerName,
            amount = BigDecimal("125.50"),
        )
}
