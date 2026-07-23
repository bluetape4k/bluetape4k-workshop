package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration

internal class EventSourcedMetricsTest {

    @Test
    fun `operational meters expose bounded projection and bulkhead evidence`() {
        val registry = SimpleMeterRegistry()
        val metrics = EventSourcedMetrics(registry)
        val gate = EventSourcedDatabasePermitGate(metrics = metrics)
        metrics.bind(gate)

        metrics.projectionLag(events = 12, age = Duration.ofSeconds(60), checkpointStalled = Duration.ofSeconds(30))
        metrics.poisoned(reasonClass = "HANDLER_REJECTED")
        metrics.poisoned(reasonClass = "raw-user-device-ip-token")
        metrics.retried()
        metrics.rebuildProgress(0.5, ProjectionGenerationState.BUILDING)
        metrics.rebuildCompleted(Duration.ofSeconds(2), OperatorAuditOutcome.APPLIED)
        gate.withPermit(EventSourcedDatabaseLane.FOREGROUND) {
            registry.get("voucher_db_bulkhead_active").gauge().value() shouldBeEqualTo 1.0
        }

        registry.get("voucher_projection_lag_events").gauge().value() shouldBeEqualTo 12.0
        registry.get("voucher_projection_lag_age_seconds").gauge().value() shouldBeEqualTo 60.0
        registry.get("voucher_projection_checkpoint_stalled_seconds").gauge().value() shouldBeEqualTo 30.0
        registry.get("voucher_projection_poison_events").counter().count() shouldBeEqualTo 1.0
        registry.get("voucher_projection_retry_total").counter().count() shouldBeEqualTo 1.0
        registry.get("voucher_rebuild_progress_ratio").gauge().value() shouldBeEqualTo 0.5
        registry.get("voucher_rebuild_duration_seconds").timer().count() shouldBeEqualTo 1L
        registry.find("voucher_rebuild_duration_seconds").tag("outcome", "APPLIED").timer()
            .shouldNotBeNull()
            .count() shouldBeEqualTo 1L
        registry.get("voucher_db_bulkhead_queued").gauge().value().shouldBeGreaterOrEqualTo(0.0)
        registry.meters
            .flatMap { meter -> meter.id.tags.map { tag -> "${tag.key}=${tag.value}" } }
            .joinToString() shouldNotContain "raw-user-device-ip-token"
    }
}
