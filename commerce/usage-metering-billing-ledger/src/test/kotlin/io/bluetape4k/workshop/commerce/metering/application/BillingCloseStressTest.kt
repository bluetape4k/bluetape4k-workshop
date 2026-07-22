@file:Suppress("MagicNumber") // The data volume and batch size are the stress contract under test.

package io.bluetape4k.workshop.commerce.metering.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.metering.config.MeteringProperties
import io.bluetape4k.workshop.commerce.metering.domain.CloseRunState
import io.bluetape4k.workshop.commerce.metering.domain.MeterCode
import io.bluetape4k.workshop.commerce.metering.domain.TenantId
import io.bluetape4k.workshop.commerce.metering.domain.UnitPrice
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriodRepository
import io.bluetape4k.workshop.commerce.metering.persistence.CloseRunRepository
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntries
import io.bluetape4k.workshop.commerce.metering.persistence.MeterRepository
import io.bluetape4k.workshop.commerce.metering.persistence.MeteringDatabaseFixture
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionRepository
import io.bluetape4k.workshop.commerce.metering.persistence.PricingScheduleRepository
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEventRepository
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEvents
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID

@Tag("stress")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BillingCloseStressTest {
    private val fixture = MeteringDatabaseFixture()
    private val clock = Clock.fixed(Instant.parse("2026-07-04T00:00:00Z"), ZoneOffset.UTC)
    private val properties = MeteringProperties(close = MeteringProperties.Close(batchSize = 500))
    private val meters = MeterRepository()
    private val prices = PriceVersionRepository()
    private val periods = BillingPeriodRepository()
    private val runs = CloseRunRepository()
    private val usage = UsageEventRepository()

    @AfterAll
    fun close(): Unit = fixture.close()

    @Test
    fun `ten thousand usage rows close exactly once across bounded restartable batches`() {
        val seed = fixture.resetAndSeed()
        val tenant = TenantId(seed.tenantId)
        val currency = Currency.getInstance(seed.currency)
        transaction {
            PriceActivationService(meters, PricingScheduleRepository(), prices, clock).activate(
                tenant,
                MeterCode(seed.meterCode),
                currency,
                UnitPrice(BigDecimal("0.010000")),
                Instant.parse("2026-06-01T00:00:00Z"),
            )
            insertUsage(seed.tenantId, seed.meterId)
        }
        val periodService = BillingPeriodService(periods, properties, clock)
        val run = transaction {
            val periodId = periodService.create(
                tenant,
                currency,
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"),
            )
            periodService.startClose(tenant, periodId)
        }

        var result = CloseBatchResult(run.id, CloseRunState.RUNNING, 0, 0, 0)
        while (result.state == CloseRunState.RUNNING) {
            val restartedWorker = BillingCloseService(runs, periods, usage, prices, properties, clock)
            result = transaction { restartedWorker.processNextBatch(seed.tenantId, run.id) }
        }

        result.state shouldBeEqualTo CloseRunState.READY_TO_FINALIZE
        transaction {
            LedgerEntries.selectAll().where { LedgerEntries.tenantId eq seed.tenantId }.count()
        } shouldBeEqualTo USAGE_COUNT.toLong()
        transaction { runs.findTenant(run.id, seed.tenantId)?.scannedCount } shouldBeEqualTo USAGE_COUNT.toLong()
    }

    private fun insertUsage(tenantId: String, meterId: UUID) {
        val occurredAt = Instant.parse("2026-06-15T00:00:00Z")
        UsageEvents.batchInsert(0 until USAGE_COUNT) { sequence ->
            val eventId = UUID.nameUUIDFromBytes("usage-$sequence".toByteArray(UTF_8))
            this[UsageEvents.id] = eventId
            this[UsageEvents.tenantId] = tenantId
            this[UsageEvents.sourceSystem] = "stress-producer"
            this[UsageEvents.sourceEventId] = "usage-$sequence"
            this[UsageEvents.requestFingerprint] = CommandFingerprint.key("usage-$sequence").value
            this[UsageEvents.meterId] = meterId
            this[UsageEvents.quantity] = BigDecimal.ONE
            this[UsageEvents.occurredAt] = occurredAt
            this[UsageEvents.receivedAt] = occurredAt
            this[UsageEvents.acceptedActor] = tenantId
            this[UsageEvents.correlationId] = null
        }
    }

    private fun <T> transaction(block: () -> T): T = fixture.executor.transaction { block() }

    private companion object {
        const val USAGE_COUNT = 10_000
    }
}
