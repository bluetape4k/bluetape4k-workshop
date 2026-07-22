package io.bluetape4k.workshop.commerce.metering.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.metering.config.MeteringProperties
import io.bluetape4k.workshop.commerce.metering.domain.CloseRunState
import io.bluetape4k.workshop.commerce.metering.domain.LedgerEntryType
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriodRepository
import io.bluetape4k.workshop.commerce.metering.persistence.CloseRunRepository
import io.bluetape4k.workshop.commerce.metering.persistence.CloseRuns
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntries
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionRepository
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionEntity
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEventEntity
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEventRepository
import io.bluetape4k.workshop.commerce.metering.persistence.UsageCloseQuery
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.core.eq
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.RoundingMode
import java.time.Clock
import java.util.UUID

private const val MONEY_SCALE = 6

data class CloseBatchResult(
    val runId: UUID,
    val state: CloseRunState,
    val scanned: Int,
    val priced: Int,
    val unpriced: Int,
)

@Service
class BillingCloseService(
    private val runs: CloseRunRepository,
    private val periods: BillingPeriodRepository,
    private val usageEvents: UsageEventRepository,
    private val prices: PriceVersionRepository,
    private val properties: MeteringProperties,
    private val clock: Clock,
) {
    @Transactional
    fun processNextBatch(tenantId: String, runId: UUID, requestedBatchSize: Int? = null): CloseBatchResult {
        CloseRuns.selectAll().where { CloseRuns.id eq runId }.forUpdate().singleOrNull()
            ?: error("close_run_not_found")
        val run = requireNotNull(runs.findTenant(runId, tenantId)) { "close_run_not_found" }
        require(run.state == CloseRunState.RUNNING.name) { "close_run_not_running" }
        val period = requireNotNull(periods.findTenant(run.periodId.value, tenantId))
        val batchSize = (requestedBatchSize ?: properties.close.batchSize).coerceAtMost(properties.close.maxBatchSize)
        require(batchSize > 0) { "invalid_batch_size" }
        val batch = usageEvents.closeBatch(UsageCloseQuery(
            tenantId = tenantId,
            startsAt = period.startsAt,
            endsAt = period.endsAt,
            cutoff = run.cutoffReceivedAt,
            lastOccurredAt = run.lastOccurredAt,
            lastId = run.lastUsageEventId,
            limit = batchSize,
        ))
        var priced = 0
        var unpriced = 0
        batch.forEach { usage ->
            val price = prices.select(tenantId, usage.meterId.value, period.currency, usage.occurredAt)
            if (price == null) {
                unpriced++
            } else {
                appendCharge(tenantId, period.id.value, period.currency, usage, price)
                priced++
            }
        }
        batch.lastOrNull()?.let {
            run.lastOccurredAt = it.occurredAt
            run.lastUsageEventId = it.id.value
        }
        run.scannedCount += batch.size
        run.pricedCount += priced
        run.unpricedCount += unpriced
        run.checkpointVersion += 1
        run.updatedAt = clock.instant()
        if (batch.size < batchSize) {
            run.state =
                if (run.unpricedCount == 0L) {
                    CloseRunState.READY_TO_FINALIZE.name
                } else {
                    CloseRunState.FAILED_VALIDATION.name
                }
            run.lastErrorCategory = if (run.unpricedCount == 0L) null else "PRICE_NOT_FOUND"
        }
        return CloseBatchResult(runId, CloseRunState.valueOf(run.state), batch.size, priced, unpriced)
    }

    @Transactional
    fun processAvailable(maxRuns: Int = properties.close.maxBatchesPerTick): Int {
        var processed = 0
        runs.running(maxRuns).forEach { run ->
            processNextBatch(run.tenantId, run.id.value)
            processed++
        }
        return processed
    }

    @Transactional
    fun resumeAfterPriceRepair(tenantId: String, runId: UUID): CloseBatchResult {
        CloseRuns.selectAll().where { CloseRuns.id eq runId }.forUpdate().singleOrNull()
            ?: error("close_run_not_found")
        val run = requireNotNull(runs.findTenant(runId, tenantId)) { "close_run_not_found" }
        require(run.state == CloseRunState.FAILED_VALIDATION.name) { "close_run_not_failed_validation" }
        run.state = CloseRunState.RUNNING.name
        run.lastOccurredAt = null
        run.lastUsageEventId = null
        run.scannedCount = 0L
        run.pricedCount = 0L
        run.unpricedCount = 0L
        run.lastErrorCategory = null
        run.checkpointVersion += 1
        run.updatedAt = clock.instant()
        return CloseBatchResult(runId, CloseRunState.RUNNING, 0, 0, 0)
    }

    private fun appendCharge(
        tenantId: String,
        periodId: UUID,
        currency: String,
        usage: UsageEventEntity,
        price: PriceVersionEntity,
    ) {
        val amount = usage.quantity.multiply(price.unitPrice).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        LedgerEntries.insertIgnore {
            it[id] = Uuid.V7.nextId()
            it[LedgerEntries.tenantId] = tenantId
            it[postingPeriodId] = periodId
            it[servicePeriodId] = periodId
            it[entryType] = LedgerEntryType.CHARGE.name
            it[sourceReferenceType] = "USAGE_EVENT"
            it[sourceReferenceId] = usage.id.value.toString()
            it[meterId] = usage.meterId.value
            it[priceVersionId] = price.id.value
            it[quantity] = usage.quantity
            it[unitPrice] = price.unitPrice
            it[LedgerEntries.amount] = amount
            it[LedgerEntries.currency] = currency
            it[relatedOriginalEntryId] = null
            it[reason] = null
            it[actor] = "billing-close"
            it[createdAt] = clock.instant()
        }
    }
}
