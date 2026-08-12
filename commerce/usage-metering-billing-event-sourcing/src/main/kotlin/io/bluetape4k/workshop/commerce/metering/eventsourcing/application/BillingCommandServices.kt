package io.bluetape4k.workshop.commerce.metering.eventsourcing.application

import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingCloseStarted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingPeriodOpened
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingPeriodReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingPeriodState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventMetadata
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.InvoiceIssued
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.InvoiceReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.InvoiceState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterRegistered
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PriceActivated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageAccepted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.AggregateReplayer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.AggregateReplayRequest
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventStore
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.OptimisticConcurrencyException
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.ReplayPolicy
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.ReplayTelemetry
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

@Service
class MeterCommandService(
    private val eventStore: EventStore,
    private val codec: DomainEventJsonCodec,
    metrics: ReplayTelemetry? = null,
) {
    private val replayRuntime = ReplayRuntime(eventStore, codec, metrics)

    fun register(tenantId: String, meterCode: String, unit: String, currency: String, now: Instant) = register(
        MeterRegistration(tenantId, meterCode, unit, currency, now),
    )

    fun register(command: MeterRegistration) {
        val stream = StreamKey(command.tenantId, "Meter", command.meterCode)
        val current = replay(stream, MeterState.Empty, MeterReducer, replayRuntime)
        check(current.state == MeterState.Empty) { "meter_already_registered" }
        val domainEvent = MeterRegistered(command.meterCode, command.unit, command.currency)
        val event = command.metadata?.let { codec.encode(domainEvent, command.occurredAt, metadata = it) }
            ?: codec.encode(domainEvent, command.occurredAt)
        eventStore.append(stream, current.streamVersion, listOf(event))
    }

    fun activatePrice(
        tenantId: String,
        meterCode: String,
        unitPrice: BigDecimal,
        currency: String,
        effectiveFrom: Instant,
    ) {
        val stream = StreamKey(tenantId, "Meter", meterCode)
        val current = replay(stream, MeterState.Empty, MeterReducer, replayRuntime)
        val event = PriceActivated(currency, unitPrice, effectiveFrom)
        MeterReducer.evolve(current.state, event)
        eventStore.append(stream, current.streamVersion, listOf(codec.encode(event, effectiveFrom)))
    }
}

data class MeterRegistration(
    val tenantId: String,
    val meterCode: String,
    val unit: String,
    val currency: String,
    val occurredAt: Instant,
    val metadata: EventMetadata? = null,
)

data class UsageAcceptance(val created: Boolean, val stream: StreamKey, val streamVersion: Long)

@Service
class UsageCommandService(
    private val eventStore: EventStore,
    private val codec: DomainEventJsonCodec,
    metrics: ReplayTelemetry? = null,
) {
    private val replayRuntime = ReplayRuntime(eventStore, codec, metrics)

    fun accept(tenantId: String, usage: UsageAccepted, now: Instant): UsageAcceptance {
        val stream = StreamKey(tenantId, "Usage", deterministicUsageId(usage.sourceSystem, usage.sourceEventId))
        val current = replay(stream, UsageState.Empty, UsageReducer, replayRuntime)
        if (current.state is UsageState.Accepted) return existing(current.state, usage, stream, current.streamVersion)
        return try {
            val appended = eventStore.append(stream, 0, listOf(codec.encode(usage, now))).single()
            UsageAcceptance(true, stream, appended.streamVersion)
        } catch (_: OptimisticConcurrencyException) {
            val winner = replay(stream, UsageState.Empty, UsageReducer, replayRuntime)
            existing(winner.state as UsageState.Accepted, usage, stream, winner.streamVersion)
        }
    }

    private fun existing(
        accepted: UsageState.Accepted,
        requested: UsageAccepted,
        stream: StreamKey,
        version: Long,
    ): UsageAcceptance {
        check(accepted.usage == requested) { "usage_source_conflict" }
        return UsageAcceptance(false, stream, version)
    }

    private fun deterministicUsageId(sourceSystem: String, sourceEventId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$sourceSystem\u0000$sourceEventId".toByteArray(UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
}

@Service
class BillingLifecycleCommandService(
    private val eventStore: EventStore,
    private val codec: DomainEventJsonCodec,
    metrics: ReplayTelemetry? = null,
) {
    private val replayRuntime = ReplayRuntime(eventStore, codec, metrics)

    fun open(tenantId: String, periodId: String, currency: String, startsAt: Instant, endsAt: Instant) {
        require(startsAt < endsAt) { "billing_period_range_invalid" }
        val stream = StreamKey(tenantId, "BillingPeriod", periodId)
        val current = replay(stream, BillingPeriodState.Empty, BillingPeriodReducer, replayRuntime)
        check(current.state == BillingPeriodState.Empty) { "billing_period_already_opened" }
        eventStore.append(stream, 0, listOf(codec.encode(BillingPeriodOpened(currency, startsAt, endsAt), startsAt)))
    }

    fun startClose(tenantId: String, periodId: String, cutoff: Instant) {
        val stream = StreamKey(tenantId, "BillingPeriod", periodId)
        val current = replay(stream, BillingPeriodState.Empty, BillingPeriodReducer, replayRuntime)
        val event = BillingCloseStarted(cutoff)
        BillingPeriodReducer.evolve(current.state, event)
        eventStore.append(stream, current.streamVersion, listOf(codec.encode(event, cutoff)))
    }

    fun issueInvoice(tenantId: String, invoiceId: String, total: BigDecimal, currency: String, now: Instant): Boolean {
        val stream = StreamKey(tenantId, "Invoice", invoiceId)
        val current = replay(stream, InvoiceState.Empty, InvoiceReducer, replayRuntime)
        if (current.state is InvoiceState.Issued) {
            check(current.state.total == total && current.state.currency == currency) { "invoice_conflict" }
            return false
        }
        eventStore.append(stream, 0, listOf(codec.encode(InvoiceIssued(invoiceId, total, currency), now)))
        return true
    }
}

internal fun <S> replay(
    stream: StreamKey,
    initialState: S,
    reducer: io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AggregateReducer<S>,
    runtime: ReplayRuntime,
) = AggregateReplayer.replay(
    AggregateReplayRequest(
        runtime.eventStore.load(stream),
        initialState,
        reducer,
        null,
        ReplayPolicy(runtime.codec.registry, 1),
    ),
    runtime.metrics,
)

internal data class ReplayRuntime(
    val eventStore: EventStore,
    val codec: DomainEventJsonCodec,
    val metrics: ReplayTelemetry?,
)
