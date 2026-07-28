package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.support.requireEquals
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags

/** low-cardinality metrics facade입니다. sale, buyer, IP, operation, attempt ID는 tag로 금지합니다. */
class TicketMetrics(private val registry: MeterRegistry) {
    private val outcomes = mutableMapOf<String, Counter>()

    fun recordOutcome(outcome: String) {
        (outcome in ALLOWED_OUTCOMES).requireEquals(true, "allowedOutcome")
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
