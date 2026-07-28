package io.bluetape4k.workshop.messaging.fallback.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * direct publish, fallback, relay, reconciliation outcome 을 기록하는 Micrometer metric helper 입니다.
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
