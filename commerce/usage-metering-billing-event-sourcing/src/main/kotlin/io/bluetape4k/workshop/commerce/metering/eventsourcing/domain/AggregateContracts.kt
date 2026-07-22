package io.bluetape4k.workshop.commerce.metering.eventsourcing.domain

fun interface AggregateReducer<S> {
    fun evolve(state: S, event: DomainEvent): S
}

data class ReplayedAggregate<S>(
    val state: S,
    val streamVersion: Long,
    val lastEventHash: String?,
)
