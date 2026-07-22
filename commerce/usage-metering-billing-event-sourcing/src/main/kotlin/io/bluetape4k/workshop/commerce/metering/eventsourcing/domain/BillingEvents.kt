package io.bluetape4k.workshop.commerce.metering.eventsourcing.domain

import java.math.BigDecimal
import java.time.Instant

data class BillingPeriodOpened(
    val currency: String,
    val startsAt: Instant,
    val endsAt: Instant,
) : DomainEvent {
    override val eventType: String = "billing-period.opened"
    override val schemaVersion: Int = 1
}

data class BillingCloseStarted(val cutoff: Instant) : DomainEvent {
    override val eventType: String = "billing-period.close-started"
    override val schemaVersion: Int = 1
}

data class BillingPeriodFinalized(val total: BigDecimal, val currency: String) : DomainEvent {
    override val eventType: String = "billing-period.finalized"
    override val schemaVersion: Int = 1
}

data class InvoiceIssued(val invoiceId: String, val total: BigDecimal, val currency: String) : DomainEvent {
    override val eventType: String = "invoice.issued"
    override val schemaVersion: Int = 1
}

enum class AdjustmentDirection { DEBIT, CREDIT }

data class AdjustmentPosted(
    val direction: AdjustmentDirection,
    val amount: BigDecimal,
    val currency: String,
    val reason: String,
    val sourceEventId: String,
) : DomainEvent {
    override val eventType: String = "adjustment.posted"
    override val schemaVersion: Int = 1
}
