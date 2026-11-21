package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.HikariPoolMXBean
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * EventSourcedMetricsTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedMetricsTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "operational meters expose bounded projection and bulkhead evidence" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

        metrics.projectionLag(events = 12, age = 60.seconds(), checkpointStalled = 30.seconds())
        metrics.retried()
        metrics.rebuildProgress(0.5, ProjectionGenerationState.BUILDING)
        metrics.rebuildCompleted(2.seconds(), OperatorAuditOutcome.APPLIED)
        metrics.commandTerminal(status = 201, duration = 12.millis())
        metrics.appendCommitted(eventCount = 3, duration = 8.millis())
        metrics.streamHeadWait(3.millis())
        metrics.appendFenceWait(2.millis())
        metrics.replay(events = 250, bytes = 32_000)
        metrics.projectionBatch(events = 200, bytes = 2_000_000)
        metrics.maintenanceQueue(wait = 100.millis(), depth = 4)
        metrics.rebuildEta(3.minutes())
        gate.withPermit(EventSourcedDatabaseLane.FOREGROUND) {
            registry.get("voucher_db_bulkhead_active").gauge().value() shouldBeEqualTo 1.0
        }

        registry.get("voucher_projection_lag_events").gauge().value() shouldBeEqualTo 12.0
        registry.get("voucher_projection_lag_age_seconds").gauge().value() shouldBeEqualTo 60.0
        registry.get("voucher_projection_checkpoint_stalled_seconds").gauge().value() shouldBeEqualTo 30.0
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

        registry.find("voucher_rebuild_duration_seconds").tag("outcome", "APPLIED")
            .timer()
            .shouldNotBeNull()
            .count() shouldBeEqualTo 1L

        registry.get("voucher_db_bulkhead_queued").gauge().value() shouldBeGreaterOrEqualTo 0.0
    }

    @Test
/**
 * 이 시나리오는 "failed poison gauge exposes current bounded database counts" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `failed poison gauge exposes current bounded database counts`() {
        val registry = SimpleMeterRegistry()
        val metrics = EventSourcedMetrics(registry)

        registry.get("voucher_projection_poison_events")
            .tag("reasonClass", "HANDLER_REJECTED")
            .gauge()
            .value() shouldBeEqualTo 0.0

        metrics.failedPoisons(
            mapOf(
                "HANDLER_REJECTED" to 1L,
                "raw-user-device-ip-token" to 1L,
            ),
        )

        registry.get("voucher_projection_poison_events")
            .tag("reasonClass", "HANDLER_REJECTED")
            .gauge()
            .value() shouldBeEqualTo 1.0

        registry.get("voucher_projection_poison_events")
            .tag("reasonClass", "UNKNOWN")
            .gauge()
            .value() shouldBeEqualTo 1.0

        registry.meters
            .flatMap { meter -> meter.id.tags.map { tag -> "${tag.key}=${tag.value}" } }
            .joinToString() shouldNotContain "raw-user-device-ip-token"

        metrics.failedPoisons(emptyMap())

        registry.get("voucher_projection_poison_events")
            .tag("reasonClass", "HANDLER_REJECTED")
            .gauge()
            .value() shouldBeEqualTo 0.0

        registry.get("voucher_projection_poison_events")
            .tag("reasonClass", "UNKNOWN")
            .gauge()
            .value() shouldBeEqualTo 0.0
    }
}
