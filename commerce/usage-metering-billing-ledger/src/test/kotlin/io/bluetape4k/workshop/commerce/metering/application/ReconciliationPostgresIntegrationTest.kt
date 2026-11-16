@file:Suppress("MaxLineLength") // Full timestamps and digest transitions are part of the audit scenario.

package io.bluetape4k.workshop.commerce.metering.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.config.MeteringProperties
import io.bluetape4k.workshop.commerce.metering.domain.MeterCode
import io.bluetape4k.workshop.commerce.metering.domain.ReconciliationFindingType
import io.bluetape4k.workshop.commerce.metering.domain.TenantId
import io.bluetape4k.workshop.commerce.metering.domain.UnitPrice
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriodEntity
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriodRepository
import io.bluetape4k.workshop.commerce.metering.persistence.CloseRunRepository
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceLineEntity
import io.bluetape4k.workshop.commerce.metering.persistence.InvoiceRepository
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntries
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntryEntity
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntryRepository
import io.bluetape4k.workshop.commerce.metering.persistence.MeterRepository
import io.bluetape4k.workshop.commerce.metering.persistence.MeteringDatabaseFixture
import io.bluetape4k.workshop.commerce.metering.persistence.MeteringSeed
import io.bluetape4k.workshop.commerce.metering.persistence.Meters
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionRepository
import io.bluetape4k.workshop.commerce.metering.persistence.PricingScheduleRepository
import io.bluetape4k.workshop.commerce.metering.persistence.ReconciliationFindingEntity
import io.bluetape4k.workshop.commerce.metering.persistence.ReconciliationFindings
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEventEntity
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEventRepository
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEvents
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.*


@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconciliationPostgresIntegrationTest {

    companion object: KLogging()

    private val fixture = MeteringDatabaseFixture()
    private val clock = Clock.fixed(Instant.parse("2026-07-04T00:00:00Z"), ZoneOffset.UTC)
    private val properties = MeteringProperties()
    private val meters = MeterRepository()
    private val prices = PriceVersionRepository()
    private val periods = BillingPeriodRepository()
    private val runs = CloseRunRepository()
    private val ledger = LedgerEntryRepository()
    private val usageEvents = UsageEventRepository()
    private val adjustments = AdjustmentService(ledger, prices, clock)
    private val reconciliation = ReconciliationService(properties, prices, adjustments, clock)

    @AfterAll
    fun close(): Unit = fixture.close()


    private fun <T> transaction(block: () -> T): T = fixture.executor.transaction { block() }

    @Test
    fun `reconciliation records all six immutable finding categories without repairing authority`() {
        val scenario = transaction { finalizedScenario() }
        val beforeCount = transaction { LedgerEntries.selectAll().count() }

        transaction {
            scenario.charge.amount += BigDecimal.ONE
            scenario.period.currency = "EUR"
            scenario.invoiceLine.amount += BigDecimal("2")
            newUsage(scenario.seed, "missing-before", scenario.cutoff.minusSeconds(1))
            newUsage(scenario.seed, "missing-after", scenario.cutoff + 1.seconds())
        }

        val result = transaction { reconciliation.inspect(scenario.seed.tenantId) }
        val types = transaction {
            ReconciliationFindingEntity
                .find { ReconciliationFindings.runId eq result.runId }
                .map { ReconciliationFindingType.valueOf(it.findingType) }
                .toSet()
        }

        types shouldBeEqualTo ReconciliationFindingType.entries.toSet()
        transaction { LedgerEntries.selectAll().count() } shouldBeEqualTo beforeCount
    }

    @Test
    fun `late usage repair rejects a stale finding after one append`() {
        val scenario = transaction { finalizedScenario() }
        transaction {
            BillingPeriodService(periods, properties, clock)
                .create(
                    tenantId = TenantId(scenario.seed.tenantId),
                    currency = Currency.getInstance(scenario.seed.currency),
                    startsAt = Instant.parse("2026-07-01T00:00:00Z"),
                    endsAt = Instant.parse("2026-08-01T00:00:00Z"),
                )
            newUsage(
                seed = scenario.seed,
                sourceId = "late-repair",
                receivedAt = scenario.cutoff + 1.seconds()
            )
        }
        val result = transaction { reconciliation.inspect(scenario.seed.tenantId) }
        val finding = transaction {
            ReconciliationFindingEntity
                .find {
                    (ReconciliationFindings.runId eq result.runId) and
                            (ReconciliationFindings.findingType eq ReconciliationFindingType.UNLEDGERED_USAGE_AFTER_CUTOFF.name)
                }
                .single()
        }
        val expectedDigest = finding.expectedDigest.shouldNotBeNull()

        transaction {
            reconciliation.repairLateUsage(
                scenario.seed.tenantId,
                finding.id.value,
                expectedDigest,
                scenario.seed.currency,
            )
        }
        assertFailsWith<IllegalStateException> {
            transaction {
                reconciliation.repairLateUsage(
                    scenario.seed.tenantId,
                    finding.id.value,
                    expectedDigest,
                    scenario.seed.currency,
                )
            }
        }
    }

    @Test
    fun `keyset scan reaches a finding after a full page of normal authority rows`() {
        val scenario = transaction { finalizedScenario() }
        transaction {
            repeat(205) { index ->
                newUsage(
                    scenario.seed,
                    "outside-period-$index",
                    scenario.cutoff - 1.seconds(),
                    Instant.parse("2025-01-01T00:00:00Z") + index.seconds(),
                )
            }
            newUsage(scenario.seed, "finding-after-prefix", scenario.cutoff + 1.seconds())
        }

        val result = transaction {
            reconciliation.inspect(scenario.seed.tenantId, requestedPageSize = 1)
        }
        val finding = transaction {
            ReconciliationFindingEntity
                .find { ReconciliationFindings.runId eq result.runId }
                .single()
        }

        finding.resourceId shouldBeEqualTo transaction {
            UsageEventEntity
                .find { UsageEvents.sourceEventId eq "finding-after-prefix" }
                .single()
                .id.value.toString()
        }
        result.hasMore.shouldBeFalse()
    }

    private fun finalizedScenario(): FinalizedScenario {
        val seed = fixture.resetAndSeed()
        val tenant = TenantId(seed.tenantId)
        val currency = Currency.getInstance(seed.currency)

        PriceActivationService(meters, PricingScheduleRepository(), prices, clock)
            .activate(
                tenantId = tenant,
                meterCode = MeterCode(seed.meterCode),
                currency = currency,
                unitPrice = UnitPrice(BigDecimal("0.125")),
                effectiveFrom = Instant.parse("2026-06-01T00:00:00Z"),
            )
        newUsage(
            seed = seed,
            sourceId = "billed",
            receivedAt = Instant.parse("2026-06-15T00:00:00Z"),
            occurredAt = Instant.parse("2026-06-15T00:00:00Z")
        )
        val periodService = BillingPeriodService(periods, properties, clock)
        val periodId = periodService.create(
            tenantId = tenant,
            currency = currency,
            startsAt = Instant.parse("2026-06-01T00:00:00Z"),
            endsAt = Instant.parse("2026-07-01T00:00:00Z"),
        )
        val run = periodService.startClose(tenant, periodId)

        BillingCloseService(runs, periods, usageEvents, prices, properties, clock)
            .processNextBatch(
                seed.tenantId,
                run.id
            )
        InvoiceService(InvoiceRepository(), periods, runs, ledger, clock)
            .finalize(seed.tenantId, run.id)

        val period = periods.findTenant(periodId, seed.tenantId).shouldNotBeNull()
        return FinalizedScenario(
            seed = seed,
            period = period,
            cutoff = requireNotNull(period.cutoffReceivedAt),
            charge = ledger.forPostingPeriod(seed.tenantId, periodId).single(),
            invoiceLine = InvoiceLineEntity.all().single(),
        )
    }

    private fun newUsage(
        seed: MeteringSeed,
        sourceId: String,
        receivedAt: Instant,
        occurredAt: Instant = Instant.parse("2026-06-20T00:00:00Z"),
    ): UsageEventEntity =
        UsageEventEntity.new {
            tenantId = seed.tenantId
            sourceSystem = "reconciliation-test"
            sourceEventId = sourceId
            requestFingerprint = CommandFingerprint.key(sourceId).value
            meterId = EntityID(seed.meterId, Meters)
            quantity = BigDecimal.TEN
            this.occurredAt = occurredAt
            this.receivedAt = receivedAt
            acceptedActor = seed.tenantId
            correlationId = null
        }
}

private data class FinalizedScenario(
    val seed: MeteringSeed,
    val period: BillingPeriodEntity,
    val cutoff: Instant,
    val charge: LedgerEntryEntity,
    val invoiceLine: InvoiceLineEntity,
)
