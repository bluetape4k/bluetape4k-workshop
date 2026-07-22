package io.bluetape4k.workshop.commerce.metering.eventsourcing.application

import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingCloseBatchRated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingPeriodFinalized
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingPeriodReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingPeriodState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageAccepted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageRated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.priceAt
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventStore
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventTypeQuery
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.OccurredEventCursor
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.OptimisticConcurrencyException
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.StreamAppend
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

enum class BillingCloseBatchResult { APPLIED, FINALIZED, ALREADY_FINALIZED, RETRY }

@Service
class BillingCloseService(
    private val eventStore: EventStore,
    private val codec: DomainEventJsonCodec,
) {
    fun closeNextBatch(tenantId: String, periodId: String, batchSize: Int, now: Instant): BillingCloseBatchResult {
        val periodStream = StreamKey(tenantId, "BillingPeriod", periodId)
        val period = replay(periodStream, BillingPeriodState.Empty, BillingPeriodReducer, codec, eventStore)
        if (period.state is BillingPeriodState.Finalized) return BillingCloseBatchResult.ALREADY_FINALIZED
        val closing = period.state as? BillingPeriodState.Closing ?: error("billing_period_not_closing")
        val cursor = closing.throughOccurredAt?.let {
            OccurredEventCursor(it, java.util.UUID.fromString(checkNotNull(closing.throughEventId)))
        }
        val usages = eventStore.loadByType(
            EventTypeQuery(tenantId, "usage.accepted", closing.startsAt, closing.endsAt, cursor, batchSize),
        )
        return try {
            if (usages.isEmpty()) finalize(periodStream, period.streamVersion, closing, now)
            else rateBatch(periodStream, period.streamVersion, closing, usages, now)
        } catch (_: OptimisticConcurrencyException) {
            BillingCloseBatchResult.RETRY
        }
    }

    private fun rateBatch(
        periodStream: StreamKey,
        periodVersion: Long,
        closing: BillingPeriodState.Closing,
        usages: List<PersistedEvent>,
        now: Instant,
    ): BillingCloseBatchResult {
        val rated = usages.map { persisted ->
            val usage = codec.registry.decode(
                persisted.eventType,
                persisted.schemaVersion,
                persisted.payload,
            ) as UsageAccepted
            val meterStream = StreamKey(persisted.stream.tenantId, "Meter", usage.meterCode)
            val meter = replay(meterStream, MeterState.Empty, MeterReducer, codec, eventStore).state
            val price = meter.priceAt(usage.occurredAt)
            val amount = usage.quantity.multiply(price.unitPrice)
            val event = UsageRated(
                persisted.eventId.toString(),
                usage.meterCode,
                usage.quantity,
                price.unitPrice,
                amount,
                price.currency,
            )
            Triple(persisted, event, amount)
        }
        val last = rated.last().first
        val batchTotal = rated.fold(BigDecimal.ZERO) { total, item -> total + item.third }
        val progress = BillingCloseBatchRated(last.occurredAt, last.eventId.toString(), batchTotal, closing.currency)
        val appends = rated.map { (persisted, event) ->
            StreamAppend(
                StreamKey(periodStream.tenantId, "Rating", persisted.eventId.toString()),
                0,
                listOf(codec.encode(event, persisted.occurredAt)),
            )
        } + StreamAppend(periodStream, periodVersion, listOf(codec.encode(progress, now)))
        eventStore.appendAll(appends)
        return BillingCloseBatchResult.APPLIED
    }

    private fun finalize(
        stream: StreamKey,
        version: Long,
        closing: BillingPeriodState.Closing,
        now: Instant,
    ): BillingCloseBatchResult {
        val event = BillingPeriodFinalized(closing.total, closing.currency)
        eventStore.append(stream, version, listOf(codec.encode(event, now)))
        return BillingCloseBatchResult.FINALIZED
    }
}
