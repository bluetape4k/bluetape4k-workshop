package io.bluetape4k.workshop.commerce.ticket.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags

/** Low-cardinality metrics facade; sale, buyer, IP, operation, and attempt IDs are forbidden as tags. */
class TicketMetrics(private val registry: MeterRegistry) {
    private val outcomes = mutableMapOf<String, Counter>()

    fun recordOutcome(outcome: String) {
        require(outcome in ALLOWED_OUTCOMES)
        outcomes.getOrPut(outcome) {
            Counter.builder("ticket.purchase.outcomes")
                .tags(Tags.of("outcome", outcome))
                .register(registry)
        }.increment()
    }

    companion object {
        private val ALLOWED_OUTCOMES = setOf("approved", "declined", "cancelled", "refunded", "quarantined")
    }
}
