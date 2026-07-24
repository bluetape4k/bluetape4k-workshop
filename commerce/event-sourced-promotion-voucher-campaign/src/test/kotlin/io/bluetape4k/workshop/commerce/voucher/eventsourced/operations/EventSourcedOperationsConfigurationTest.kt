package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.shouldNotBeNull
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

internal class EventSourcedOperationsConfigurationTest {

    @Test
    fun `configuration binds the bounded database gate meters`() {
        val registry = SimpleMeterRegistry()
        val configuration = EventSourcedOperationsConfiguration()

        val metrics = configuration.eventSourcedMetrics(registry)
        configuration.eventSourcedDatabasePermitGate(metrics)

        registry.find("voucher_db_bulkhead_active").gauge().shouldNotBeNull()
        registry.find("voucher_db_bulkhead_queued").gauge().shouldNotBeNull()
    }
}
