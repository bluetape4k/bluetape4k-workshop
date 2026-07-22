@file:Suppress("MaxLineLength") // Full timestamps remain visible at each billing boundary.

package io.bluetape4k.workshop.commerce.metering

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.metering.application.AdjustmentService
import io.bluetape4k.workshop.commerce.metering.application.BillingCloseService
import io.bluetape4k.workshop.commerce.metering.application.BillingPeriodService
import io.bluetape4k.workshop.commerce.metering.application.InvoiceService
import io.bluetape4k.workshop.commerce.metering.application.PriceActivationService
import io.bluetape4k.workshop.commerce.metering.application.ReconciliationService
import io.bluetape4k.workshop.commerce.metering.application.UsageIngestionCommand
import io.bluetape4k.workshop.commerce.metering.application.UsageIngestionService
import io.bluetape4k.workshop.commerce.metering.config.MeteringProperties
import io.bluetape4k.workshop.commerce.metering.domain.CloseRunState
import io.bluetape4k.workshop.commerce.metering.domain.MeterCode
import io.bluetape4k.workshop.commerce.metering.domain.SourceEventId
import io.bluetape4k.workshop.commerce.metering.domain.SourceSystem
import io.bluetape4k.workshop.commerce.metering.domain.TenantId
import io.bluetape4k.workshop.commerce.metering.domain.UnitPrice
import io.bluetape4k.workshop.commerce.metering.domain.UsageQuantity
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriodRepository
import io.bluetape4k.workshop.commerce.metering.persistence.CloseRunRepository
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceRepository
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntryRepository
import io.bluetape4k.workshop.commerce.metering.persistence.MeterRepository
import io.bluetape4k.workshop.commerce.metering.persistence.MeteringDatabaseFixture
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionRepository
import io.bluetape4k.workshop.commerce.metering.persistence.PricingScheduleRepository
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEventRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Currency

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MeteringEndToEndIntegrationTest {
    private val fixture = MeteringDatabaseFixture()
    private val clock = ScenarioClock(Instant.parse("2026-07-04T00:00:00Z"))
    private val properties = MeteringProperties()
    private val meters = MeterRepository()
    private val prices = PriceVersionRepository()
    private val periods = BillingPeriodRepository()
    private val runs = CloseRunRepository()
    private val ledger = LedgerEntryRepository()
    private val usageEvents = UsageEventRepository()
    private val activation = PriceActivationService(meters, PricingScheduleRepository(), prices, clock)
    private val ingestion = UsageIngestionService(meters, usageEvents, properties, clock)
    private val periodService = BillingPeriodService(periods, properties, clock)
    private val close = BillingCloseService(runs, periods, usageEvents, prices, properties, clock)
    private val invoices = InvoiceService(InvoiceRepository(), periods, runs, ledger, clock)
    private val adjustments = AdjustmentService(ledger, prices, clock)
    private val reconciliation = ReconciliationService(properties, prices, adjustments, clock)

    @AfterAll
    fun close(): Unit = fixture.close()

    @Test
    fun `usage becomes an immutable invoice and late usage becomes a linked adjustment`() {
        fixture.resetAndSeed()
        val tenant = TenantId("tenant-a")
        val meter = MeterCode("api_calls")
        val currency = Currency.getInstance("USD")
        transaction {
            activation.activate(tenant, meter, currency, UnitPrice(BigDecimal("0.125")), Instant.parse("2026-06-01T00:00:00Z"))
        }
        val first = transaction { ingest("usage-1", Instant.parse("2026-06-15T00:00:00Z")) }
        transaction { ingest("usage-1", Instant.parse("2026-06-15T00:00:00Z")) }.replayedProducerEvent shouldBeEqualTo true
        val servicePeriod = transaction {
            periodService.create(tenant, currency, Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z"))
        }
        val run = transaction { periodService.startClose(tenant, servicePeriod) }
        transaction { close.processNextBatch(tenant.value, run.id) }.state shouldBeEqualTo CloseRunState.READY_TO_FINALIZE
        val invoice = transaction { invoices.finalize(tenant.value, run.id) }
        invoice.total shouldBeEqualTo BigDecimal("1.250000")

        transaction {
            periodService.create(tenant, currency, Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"))
        }
        val late = transaction { ingest("usage-late", Instant.parse("2026-06-20T00:00:00Z")) }
        val debit = transaction { adjustments.postLateDebit(tenant.value, late.id, currency.currencyCode) }
        transaction { adjustments.postLateDebit(tenant.value, late.id, currency.currencyCode) } shouldBeEqualTo debit
        transaction { adjustments.postCredit(tenant.value, debit, "customer goodwill", "operator") }

        first.replayedProducerEvent shouldBeEqualTo false
        transaction { reconciliation.inspect(tenant.value, 200) }.findingCount shouldBeEqualTo 0
    }

    @Test
    fun `a failed close restarts from its fixed cutoff after an explicit price gap repair`() {
        fixture.resetAndSeed()
        val tenant = TenantId("tenant-a")
        val meter = MeterCode("api_calls")
        val currency = Currency.getInstance("USD")
        transaction {
            activation.activate(
                tenant,
                meter,
                currency,
                UnitPrice(BigDecimal("0.200")),
                Instant.parse("2026-07-01T00:00:00Z"),
            )
            ingest("unpriced-usage", Instant.parse("2026-06-15T00:00:00Z"))
        }
        val period = transaction {
            periodService.create(
                tenant,
                currency,
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"),
            )
        }
        val run = transaction { periodService.startClose(tenant, period) }
        transaction { close.processNextBatch(tenant.value, run.id) }.state shouldBeEqualTo
            CloseRunState.FAILED_VALIDATION

        transaction {
            activation.repairGap(
                io.bluetape4k.workshop.commerce.metering.application.PriceGapRepair(
                    tenant,
                    meter,
                    currency,
                    UnitPrice(BigDecimal("0.100")),
                    Instant.parse("2026-06-01T00:00:00Z"),
                    Instant.parse("2026-07-01T00:00:00Z"),
                ),
            )
            close.resumeAfterPriceRepair(tenant.value, run.id)
        }
        transaction { close.processNextBatch(tenant.value, run.id) }.state shouldBeEqualTo
            CloseRunState.READY_TO_FINALIZE
        transaction { ledger.forPostingPeriod(tenant.value, period).size } shouldBeEqualTo 1
    }

    private fun ingest(sourceId: String, occurredAt: Instant) =
        ingestion.ingest(
            UsageIngestionCommand(
                tenantId = TenantId("tenant-a"),
                sourceSystem = SourceSystem("gateway"),
                sourceEventId = SourceEventId(sourceId),
                meterCode = MeterCode("api_calls"),
                quantity = UsageQuantity(BigDecimal("10")),
                occurredAt = occurredAt,
                actor = "tenant-a",
                correlationId = null,
                requestFingerprint = CommandFingerprint.request("usage", mapOf("sourceEventId" to sourceId)),
            ),
        )

    private fun <T> transaction(block: () -> T): T = fixture.executor.transaction { block() }
}

private class ScenarioClock(private var current: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = current
    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
