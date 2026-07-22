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
