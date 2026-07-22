package io.bluetape4k.workshop.commerce.metering.eventsourcing.application

import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AdjustmentPosted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingCloseStarted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingCloseBatchRated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingPeriodFinalized
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.BillingPeriodOpened
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.DomainEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.InvoiceIssued
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterRegistered
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.NewEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PriceActivated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageAccepted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageRated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventCodecRegistry
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

class DomainEventJsonCodec(
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) {
    val registry: EventCodecRegistry = EventCodecRegistry().apply {
        register<MeterRegistered>()
        register<PriceActivated>()
        register<UsageAccepted>()
        register<UsageRated>()
        register<BillingPeriodOpened>()
        register<BillingCloseStarted>()
        register<BillingCloseBatchRated>()
        register<BillingPeriodFinalized>()
        register<InvoiceIssued>()
        register<AdjustmentPosted>()
    }

    fun encode(event: DomainEvent, occurredAt: Instant, eventId: UUID = UUID.randomUUID()): NewEvent = NewEvent(
        eventId = eventId,
        event = event,
        payload = mapper.writeValueAsString(event),
        metadata = "{}",
        occurredAt = occurredAt,
    )

    private inline fun <reified E : DomainEvent> EventCodecRegistry.register() {
        val sampleType = eventTypeOf(E::class.java)
        register(sampleType, 1) { payload -> mapper.readValue(payload, E::class.java) }
    }

    private fun eventTypeOf(type: Class<out DomainEvent>): String = when (type) {
        MeterRegistered::class.java -> "meter.registered"
        PriceActivated::class.java -> "price.activated"
        UsageAccepted::class.java -> "usage.accepted"
        UsageRated::class.java -> "usage.rated"
        BillingPeriodOpened::class.java -> "billing-period.opened"
        BillingCloseStarted::class.java -> "billing-period.close-started"
        BillingCloseBatchRated::class.java -> "billing-period.close-batch-rated"
        BillingPeriodFinalized::class.java -> "billing-period.finalized"
        InvoiceIssued::class.java -> "invoice.issued"
        AdjustmentPosted::class.java -> "adjustment.posted"
        else -> error("unsupported_event_type:${type.name}")
    }
}
