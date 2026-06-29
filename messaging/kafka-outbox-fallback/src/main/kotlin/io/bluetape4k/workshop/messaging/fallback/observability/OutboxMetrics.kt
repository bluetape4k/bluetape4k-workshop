package io.bluetape4k.workshop.messaging.fallback.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * Micrometer metric helper for direct publish, fallback, relay, and reconciliation outcomes.
 */
@Component
class OutboxMetrics(
    private val meterRegistry: MeterRegistry,
) {

    fun recordDirectPublish(result: String) {
        meterRegistry.counter("workshop.outbox.direct.publish.attempts", "result", result).increment()
    }

    fun recordFallbackStored(result: String) {
        meterRegistry.counter("workshop.outbox.fallback.stored", "result", result).increment()
    }

    fun recordRelay(result: String) {
        meterRegistry.counter("workshop.outbox.relay.events", "result", result).increment()
    }

    fun recordReconciler(result: String) {
        meterRegistry.counter("workshop.outbox.reconciler.events", "result", result).increment()
    }
}
