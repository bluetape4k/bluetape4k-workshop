package io.bluetape4k.workshop.commerce.usagebilling.query.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

class QueryMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = QueryMetrics(registry)

    @Test
    fun `inbox metric keeps only service outcome and event type as tags`() {
        metrics.inboxOutcome("APPLIED", "InvoiceIssued")

        requireNotNull(
            registry.find("usage_billing_inbox_outcome_total")
                .tags("service", "query", "outcome", "APPLIED", "event_type", "InvoiceIssued")
                .counter(),
        )
            .count() shouldBeEqualTo 1.0
    }
}
