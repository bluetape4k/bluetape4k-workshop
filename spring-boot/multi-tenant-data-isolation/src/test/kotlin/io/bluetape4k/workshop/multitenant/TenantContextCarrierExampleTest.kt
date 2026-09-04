package io.bluetape4k.workshop.multitenant

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.tenant.MissingTenantContextException
import io.bluetape4k.workshop.multitenant.domain.InvoiceRecord
import io.bluetape4k.workshop.multitenant.domain.TenantId
import io.bluetape4k.workshop.multitenant.service.TenantContextCarrierService
import io.bluetape4k.workshop.multitenant.service.TenantInvoiceService
import io.bluetape4k.workshop.multitenant.service.TenantMetrics
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantContextCarrierExampleTest @Autowired constructor(
    private val carrier: TenantContextCarrierService,
    private val invoiceService: TenantInvoiceService,
) {

    @AfterEach
    fun reset() {
        invoiceService.resetWorkshopState()
        carrier.currentMvcTenant().shouldBeNull()
    }

    @Test
    fun `MVC ThreadLocal carrier supplies tenant to repository and restores nested scope`() {
        val invoice = invoiceService.createInvoice(invoice(TenantId.ALPHA, "Alpha Hospital"))

        carrier.withMvcTenant(TenantId.ALPHA) {
            carrier.findInvoiceWithMvcTenant(invoice.id)?.tenantId shouldBeEqualTo TenantId.ALPHA

            carrier.withMvcTenant(TenantId.BETA) {
                carrier.currentMvcTenant() shouldBeEqualTo TenantId.BETA
                carrier.findInvoiceWithMvcTenant(invoice.id).shouldBeNull()
            }

            carrier.currentMvcTenant() shouldBeEqualTo TenantId.ALPHA
        }

        carrier.currentMvcTenant().shouldBeNull()
        assertThrows<MissingTenantContextException> {
            carrier.findInvoiceWithMvcTenant(invoice.id)
        }
    }

    @Test
    fun `MVC ThreadLocal carrier isolates concurrent requests and cleans after failure`() {
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)

        try {
            val futures = listOf(TenantId.ALPHA, TenantId.BETA).map { tenantId ->
                executor.submit<TenantId> {
                    carrier.withMvcTenant(tenantId) {
                        ready.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        carrier.requireMvcTenant()
                    }
                }
            }

            ready.await(5, TimeUnit.SECONDS).shouldBeTrue()
            release.countDown()
            futures.map { it.get(5, TimeUnit.SECONDS) }.toSet() shouldBeEqualTo
                setOf(TenantId.ALPHA, TenantId.BETA)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }

        carrier.currentMvcTenant().shouldBeNull()

        assertThrows<IllegalStateException> {
            carrier.withMvcTenant(TenantId.ALPHA) {
                error("request failed")
            }
        }
        carrier.currentMvcTenant().shouldBeNull()
    }

    @Test
    fun `virtual thread ScopedValue carrier is lexical and does not leak to the next task`() {
        val invoice = invoiceService.createInvoice(invoice(TenantId.ALPHA, "Alpha Hospital"))

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val observed = executor.submit<TenantId> {
                carrier.withVirtualThreadTenant(TenantId.ALPHA) {
                    carrier.findInvoiceWithVirtualThreadTenant(invoice.id)?.tenantId shouldBeEqualTo TenantId.ALPHA
                    carrier.withVirtualThreadTenant(TenantId.BETA) {
                        carrier.requireVirtualThreadTenant()
                    }
                }
            }.get(5, TimeUnit.SECONDS)
            observed shouldBeEqualTo TenantId.BETA

            executor.submit<TenantId?> {
                carrier.currentVirtualThreadTenant()
            }.get(5, TimeUnit.SECONDS).shouldBeNull()
        }
    }

    @Test
    fun `Reactor carrier survives scheduler hop while concurrent subscriptions remain isolated`() {
        val alphaInvoice = invoiceService.createInvoice(invoice(TenantId.ALPHA, "Alpha Hospital"))
        val betaInvoice = invoiceService.createInvoice(invoice(TenantId.BETA, "Beta Clinic"))
        val scheduler = Schedulers.newParallel("tenant-context", 2)
        try {
            val observed = Flux.merge(
                reactorTenantProbe(TenantId.ALPHA, alphaInvoice.id, scheduler),
                reactorTenantProbe(TenantId.BETA, betaInvoice.id, scheduler),
            ).collectList().block(Duration.ofSeconds(5)) ?: emptyList()

            observed.toSet() shouldBeEqualTo setOf(TenantId.ALPHA, TenantId.BETA)
        } finally {
            scheduler.dispose()
        }
    }

    @Test
    fun `Reactor carrier cancellation does not leave context behind`() {
        val started = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val subscription = carrier.withReactorTenant(
            TenantId.ALPHA,
            Mono.deferContextual { contextView ->
                carrier.requireReactorTenant(contextView)
                started.countDown()
                Mono.never<TenantId>().doOnCancel { cancelled.set(true) }
            },
        ).subscribe()

        try {
            started.await(5, TimeUnit.SECONDS).shouldBeTrue()
        } finally {
            subscription.dispose()
        }

        cancelled.get().shouldBeTrue()
        carrier.currentReactorTenant(reactor.util.context.Context.empty()).shouldBeNull()
    }

    @Test
    fun `tenant metrics expose only a stable fingerprint and never the raw tenant value`() {
        val invoice = invoiceService.createInvoice(invoice(TenantId.ALPHA, "Alpha Hospital"))
        invoiceService.findInvoice(TenantId.ALPHA, invoice.id)

        val meter = carrier.invoiceReadMeter(TenantId.ALPHA)
        meter.id.getTag("tenant").shouldBeNull()
        meter.id.getTag("tenant_fingerprint") shouldBeEqualTo TenantMetrics.tenantFingerprint(TenantId.ALPHA)
        TenantMetrics.tenantFingerprint(TenantId.ALPHA).contains(TenantId.ALPHA.value).shouldBeFalse()
    }

    private fun reactorTenantProbe(
        tenantId: TenantId,
        invoiceId: Long,
        scheduler: reactor.core.scheduler.Scheduler,
    ): Mono<TenantId> = carrier.withReactorTenant(
        tenantId,
        Mono.deferContextual { contextView ->
                invoiceService.findInvoice(carrier.requireReactorTenant(contextView), invoiceId)
                ?.tenantId
                ?.let { Mono.just(it) }
                ?.publishOn(scheduler)
                ?.flatMap {
                    Mono.deferContextual { resumed ->
                        Mono.just(carrier.requireReactorTenant(resumed))
                    }
                }
                ?: Mono.error(IllegalStateException("invoice fixture is missing"))
        },
    )

    private fun invoice(tenantId: TenantId, customerName: String): InvoiceRecord =
        InvoiceRecord(
            tenantId = tenantId,
            customerName = customerName,
            amount = BigDecimal("125.50"),
        )
}
