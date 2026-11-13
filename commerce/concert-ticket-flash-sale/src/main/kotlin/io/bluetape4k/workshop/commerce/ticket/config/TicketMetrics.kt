package io.bluetape4k.workshop.commerce.ticket.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.util.concurrent.ConcurrentHashMap

/** low-cardinality metrics facade입니다. sale, buyer, IP, operation, attempt ID는 tag로 금지합니다. */
class TicketMetrics(private val registry: MeterRegistry) {

    private val outcomes = ConcurrentHashMap<String, Counter>()

    fun recordOutcome(outcome: String) {
        val outcomeValue = outcome.lowercase()
        require(outcomeValue in ALLOWED_OUTCOMES) { "Outcome[$outcomeValue] is not allowed" }

        outcomes.computeIfAbsent(outcomeValue) {
            Counter.builder("ticket.purchase.outcomes")
                .tags(Tags.of("outcome", outcomeValue))
                .register(registry)
        }.increment()
    }

    companion object {
        private val ALLOWED_OUTCOMES = setOf("approved", "declined", "cancelled", "refunded", "quarantined")
    }
}
