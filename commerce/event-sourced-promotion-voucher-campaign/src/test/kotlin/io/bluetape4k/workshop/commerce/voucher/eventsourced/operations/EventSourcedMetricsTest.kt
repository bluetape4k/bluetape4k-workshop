package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.HikariPoolMXBean
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import io.mockk.every
import io.mockk.mockk
import java.time.Duration

internal class EventSourcedMetricsTest {

    @Test
    fun `operational meters expose bounded projection and bulkhead evidence`() {
        val registry = SimpleMeterRegistry()
        val metrics = EventSourcedMetrics(registry)
        val gate = EventSourcedDatabasePermitGate(metrics = metrics)
        val hikari = mockk<HikariDataSource>()
        val hikariPool = mockk<HikariPoolMXBean>()
        every { hikari.hikariPoolMXBean } returns hikariPool
        every { hikariPool.activeConnections } returns 2
        every { hikariPool.threadsAwaitingConnection } returns 3
        metrics.bind(gate)
        metrics.bind(hikari)

        metrics.projectionLag(events = 12, age = Duration.ofSeconds(60), checkpointStalled = Duration.ofSeconds(30))
        metrics.poisoned(reasonClass = "HANDLER_REJECTED")
        metrics.poisoned(reasonClass = "raw-user-device-ip-token")
        metrics.retried()
        metrics.rebuildProgress(0.5, ProjectionGenerationState.BUILDING)
        metrics.rebuildCompleted(Duration.ofSeconds(2), OperatorAuditOutcome.APPLIED)
        metrics.commandTerminal(status = 201, duration = Duration.ofMillis(12))
        metrics.appendCommitted(eventCount = 3, duration = Duration.ofMillis(8))
        metrics.streamHeadWait(Duration.ofMillis(3))
        metrics.appendFenceWait(Duration.ofMillis(2))
        metrics.replay(events = 250, bytes = 32_000)
        metrics.projectionBatch(events = 200, bytes = 2_000_000)
        metrics.maintenanceQueue(wait = Duration.ofMillis(100), depth = 4)
        metrics.rebuildEta(Duration.ofMinutes(3))
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
        registry.get("voucher_command_terminal").tag("status", "2xx").timer().count() shouldBeEqualTo 1L
        registry.get("voucher_event_append_committed").counter().count() shouldBeEqualTo 3.0
        registry.get("voucher_event_append_duration").timer().count() shouldBeEqualTo 1L
        registry.get("voucher_event_stream_head_wait").timer().count() shouldBeEqualTo 1L
        registry.get("voucher_event_append_fence_wait").timer().count() shouldBeEqualTo 1L
        registry.get("voucher_replay_events").summary().totalAmount() shouldBeEqualTo 250.0
        registry.get("voucher_replay_bytes").summary().totalAmount() shouldBeEqualTo 32_000.0
        registry.get("voucher_projection_batch_events").summary().totalAmount() shouldBeEqualTo 200.0
        registry.get("voucher_projection_batch_bytes").summary().totalAmount() shouldBeEqualTo 2_000_000.0
        registry.get("voucher_maintenance_queue_wait").timer().count() shouldBeEqualTo 1L
        registry.get("voucher_maintenance_queue_depth").gauge().value() shouldBeEqualTo 4.0
        registry.get("voucher_rebuild_eta_seconds").gauge().value() shouldBeEqualTo 180.0
        registry.get("voucher_db_permit_utilization").gauge().value().shouldBeGreaterOrEqualTo(0.0)
        registry.get("voucher_hikari_active").gauge().value() shouldBeEqualTo 2.0
        registry.get("voucher_hikari_waiting").gauge().value() shouldBeEqualTo 3.0
        registry.find("voucher_rebuild_duration_seconds").tag("outcome", "APPLIED").timer()
            .shouldNotBeNull()
            .count() shouldBeEqualTo 1L
        registry.get("voucher_db_bulkhead_queued").gauge().value().shouldBeGreaterOrEqualTo(0.0)
        registry.meters
            .flatMap { meter -> meter.id.tags.map { tag -> "${tag.key}=${tag.value}" } }
            .joinToString() shouldNotContain "raw-user-device-ip-token"
    }
}
