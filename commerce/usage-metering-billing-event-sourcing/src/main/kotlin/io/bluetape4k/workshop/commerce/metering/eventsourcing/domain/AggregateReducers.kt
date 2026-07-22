@file:Suppress("MatchingDeclarationName") // The file grows one reducer per aggregate boundary.

package io.bluetape4k.workshop.commerce.metering.eventsourcing.domain

object MeterReducer : AggregateReducer<MeterState> {
    override fun evolve(state: MeterState, event: DomainEvent): MeterState =
        when (event) {
            is MeterRegistered -> {
                check(state == MeterState.Empty) { "meter_already_registered" }
                MeterState.Active(event.meterCode, event.unit, event.currency, emptyList())
            }
            is PriceActivated -> {
                check(state is MeterState.Active) { "meter_not_registered" }
                check(event.currency == state.currency) { "currency_mismatch" }
                check(state.prices.none { it.effectiveFrom == event.effectiveFrom }) { "price_overlap" }
                state.copy(
                    prices = (state.prices + PricePoint(event.currency, event.unitPrice, event.effectiveFrom))
                        .sortedBy(PricePoint::effectiveFrom),
                )
            }
            else -> state
        }
}

sealed interface UsageState {
    data object Empty : UsageState
    data class Accepted(val usage: UsageAccepted) : UsageState
}

object UsageReducer : AggregateReducer<UsageState> {
    override fun evolve(state: UsageState, event: DomainEvent): UsageState = when (event) {
        is UsageAccepted -> {
            check(state == UsageState.Empty) { "usage_already_accepted" }
            UsageState.Accepted(event)
        }
        else -> state
    }
}

sealed interface BillingPeriodState {
    data object Empty : BillingPeriodState
    data class Open(
        val currency: String,
        val startsAt: java.time.Instant,
        val endsAt: java.time.Instant,
    ) : BillingPeriodState
    data class Closing(
        val currency: String,
        val startsAt: java.time.Instant,
        val endsAt: java.time.Instant,
        val cutoff: java.time.Instant,
        val throughOccurredAt: java.time.Instant? = null,
        val throughEventId: String? = null,
        val total: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    ) : BillingPeriodState
    data class Finalized(val total: java.math.BigDecimal, val currency: String) : BillingPeriodState
}

object BillingPeriodReducer : AggregateReducer<BillingPeriodState> {
    override fun evolve(state: BillingPeriodState, event: DomainEvent): BillingPeriodState = when (event) {
        is BillingPeriodOpened -> {
            check(state == BillingPeriodState.Empty) { "billing_period_already_opened" }
            BillingPeriodState.Open(event.currency, event.startsAt, event.endsAt)
        }
        is BillingCloseStarted -> {
            check(state is BillingPeriodState.Open) { "billing_period_not_open" }
            BillingPeriodState.Closing(state.currency, state.startsAt, state.endsAt, event.cutoff)
        }
        is BillingCloseBatchRated -> {
            check(state is BillingPeriodState.Closing) { "billing_period_not_closing" }
            check(state.currency == event.currency) { "currency_mismatch" }
            state.copy(
                throughOccurredAt = event.throughOccurredAt,
                throughEventId = event.throughEventId,
                total = state.total + event.batchAmount,
            )
        }
        is BillingPeriodFinalized -> {
            check(state is BillingPeriodState.Closing) { "billing_period_not_closing" }
            check(state.currency == event.currency) { "currency_mismatch" }
            check(state.total.compareTo(event.total) == 0) { "billing_period_total_mismatch" }
            BillingPeriodState.Finalized(event.total, event.currency)
        }
        else -> state
    }
}

sealed interface InvoiceState {
    data object Empty : InvoiceState
    data class Issued(val invoiceId: String, val total: java.math.BigDecimal, val currency: String) : InvoiceState
}

object InvoiceReducer : AggregateReducer<InvoiceState> {
    override fun evolve(state: InvoiceState, event: DomainEvent): InvoiceState = when (event) {
        is InvoiceIssued -> {
            check(state == InvoiceState.Empty) { "invoice_already_issued" }
            InvoiceState.Issued(event.invoiceId, event.total, event.currency)
        }
        else -> state
    }
}
