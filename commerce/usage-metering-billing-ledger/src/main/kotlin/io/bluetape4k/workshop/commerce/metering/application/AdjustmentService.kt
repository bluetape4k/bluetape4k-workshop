package io.bluetape4k.workshop.commerce.metering.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.commerce.metering.domain.BillingPeriodState
import io.bluetape4k.workshop.commerce.metering.domain.LedgerEntryType
import io.bluetape4k.workshop.commerce.metering.domain.chargeAmount
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriodEntity
import io.bluetape4k.workshop.commerce.metering.persistence.BillingPeriods
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntries
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntryEntity
import io.bluetape4k.workshop.commerce.metering.persistence.LedgerEntryRepository
import io.bluetape4k.workshop.commerce.metering.persistence.PriceVersionRepository
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEventEntity
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.util.*

@Service
class AdjustmentService(
    private val ledger: LedgerEntryRepository,
    private val prices: PriceVersionRepository,
    private val clock: Clock,
) {
    @Transactional
    fun postLateDebit(tenantId: String, usageEventId: UUID, currency: String): UUID {
        val usage = UsageEventEntity.findById(usageEventId).requireNotNull { "usage_event_not_found" }
        usage.tenantId.requireEquals(tenantId, "usage.tenantId")

        ledger.findSource(
            tenantId = tenantId,
            type = LedgerEntryType.DEBIT_ADJUSTMENT.name,
            sourceType = "USAGE_EVENT",
            sourceId = usageEventId.toString()
        )?.let { return it.id.value }

        val servicePeriod = BillingPeriodEntity.find {
            (BillingPeriods.tenantId eq tenantId) and
                    (BillingPeriods.currency eq currency) and
                    (BillingPeriods.state eq BillingPeriodState.FINALIZED.name) and
                    (BillingPeriods.startsAt lessEq usage.occurredAt) and
                    (BillingPeriods.endsAt greater usage.occurredAt)
        }.singleOrNull() ?: error("finalized_service_period_not_found")

        val postingPeriod = BillingPeriodEntity.find {
            val now = clock.instant()
            (BillingPeriods.tenantId eq tenantId) and
                    (BillingPeriods.currency eq currency) and
                    (BillingPeriods.state eq BillingPeriodState.OPEN.name) and
                    (BillingPeriods.startsAt lessEq now) and
                    (BillingPeriods.endsAt greater now)
        }.singleOrNull() ?: error("open_posting_period_not_found")

        val price = prices.select(tenantId, usage.meterId.value, currency, usage.occurredAt)
            .requireNotNull { "price_not_found" }

        val id = Uuid.V7.nextId()
        LedgerEntries.insertIgnore {
            it[LedgerEntries.id] = id
            it[LedgerEntries.tenantId] = tenantId
            it[postingPeriodId] = postingPeriod.id.value
            it[LedgerEntries.servicePeriodId] = servicePeriod.id.value
            it[entryType] = LedgerEntryType.DEBIT_ADJUSTMENT.name
            it[sourceReferenceType] = "USAGE_EVENT"
            it[sourceReferenceId] = usage.id.value.toString()
            it[meterId] = usage.meterId.value
            it[priceVersionId] = price.id.value
            it[quantity] = usage.quantity
            it[unitPrice] = price.unitPrice
            it[amount] = chargeAmount(usage.quantity, price.unitPrice, currency)
            it[LedgerEntries.currency] = currency
            it[relatedOriginalEntryId] = null
            it[reason] = "late usage received after finalized cutoff"
            it[actor] = "late-usage-adjustment"
            it[createdAt] = clock.instant()
        }
        return ledger.findSource(
            tenantId,
            LedgerEntryType.DEBIT_ADJUSTMENT.name,
            "USAGE_EVENT",
            usageEventId.toString()
        )
            .requireNotNull("LedgeEntryEntity")
            .id.value
    }

    @Transactional
    fun postCredit(tenantId: String, originalEntryId: UUID, reason: String, actor: String): UUID {
        reason.requireNotBlank("reason")
        actor.requireNotBlank("actor")
        val original = LedgerEntryEntity.findById(originalEntryId).requireNotNull { "ledger_entry_not_found" }
        original.tenantId.requireEquals(tenantId, "original.tenantId")

        val sourceId = originalEntryId.toString()
        ledger.findSource(tenantId, LedgerEntryType.CREDIT_ADJUSTMENT.name, "LEDGER_ENTRY", sourceId)
            ?.let { return it.id.value }

        val postingPeriod = BillingPeriodEntity.find {
            val now = clock.instant()
            (BillingPeriods.tenantId eq tenantId) and
                    (BillingPeriods.currency eq original.currency) and
                    (BillingPeriods.state eq BillingPeriodState.OPEN.name) and
                    (BillingPeriods.startsAt lessEq now) and
                    (BillingPeriods.endsAt greater now)
        }.singleOrNull() ?: error("open_posting_period_not_found")

        val id = Uuid.V7.nextId()
        LedgerEntries.insertIgnore {
            it[LedgerEntries.id] = id
            it[LedgerEntries.tenantId] = tenantId
            it[postingPeriodId] = postingPeriod.id.value
            it[servicePeriodId] = original.servicePeriodId.value
            it[entryType] = LedgerEntryType.CREDIT_ADJUSTMENT.name
            it[sourceReferenceType] = "LEDGER_ENTRY"
            it[sourceReferenceId] = sourceId
            it[meterId] = original.meterId.value
            it[priceVersionId] = original.priceVersionId.value
            it[quantity] = original.quantity
            it[unitPrice] = original.unitPrice
            it[amount] = original.amount.abs().takeIf { value -> value > BigDecimal.ZERO } ?: error("zero_adjustment")
            it[currency] = original.currency
            it[relatedOriginalEntryId] = original.id.value
            it[LedgerEntries.reason] = reason
            it[LedgerEntries.actor] = actor
            it[createdAt] = clock.instant()
        }
        return ledger.findSource(
            tenantId = tenantId,
            type = LedgerEntryType.CREDIT_ADJUSTMENT.name,
            sourceType = "LEDGER_ENTRY",
            sourceId = sourceId
        )
            .requireNotNull("LedgerEntryEntity")
            .id.value
    }
}
