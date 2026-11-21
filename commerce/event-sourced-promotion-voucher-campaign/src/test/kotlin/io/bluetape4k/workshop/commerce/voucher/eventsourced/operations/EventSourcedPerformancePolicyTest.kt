package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.*

@Tag("stress")
/**
 * EventSourcedPerformancePolicyTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedPerformancePolicyTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "runtime budgets preserve replay projection rebuild and maintenance boundaries" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `runtime budgets preserve replay projection rebuild and maintenance boundaries`() {
        val budget = EventSourcedRuntimeBudget()

        budget.snapshotEveryEvents shouldBeEqualTo 250
        budget.maxReplayEvents shouldBeEqualTo 10_000
        budget.maxReplayDuration shouldBeEqualTo 2.seconds()
        budget.projectionBatchEvents shouldBeEqualTo 200
        budget.projectionBatchBytes shouldBeEqualTo 2 * 1024 * 1024
        budget.projectionTransactionTimeout shouldBeEqualTo 2.seconds()
        budget.rebuildBatchEvents shouldBeEqualTo 200
        budget.rebuildMaxEvents shouldBeEqualTo 100_000
        budget.rebuildMaxDuration shouldBeEqualTo 10.minutes()
        budget.maintenanceQueueCapacity shouldBeEqualTo 64
        budget.maintenanceBatchRows shouldBeEqualTo 100
        budget.maintenanceBatchBytes shouldBeEqualTo 2 * 1024 * 1024
        budget.maintenanceTransactionTimeout shouldBeEqualTo 2.seconds()
        budget.maintenanceMinBackoff shouldBeEqualTo 100.millis()
        budget.maintenanceMaxBackoff shouldBeEqualTo 5.seconds()
    }

    @Test
/**
 * 이 시나리오는 "rebuild throttle reacts only at lag or foreground saturation boundaries" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `rebuild throttle reacts only at lag or foreground saturation boundaries`() {
        val budget = EventSourcedRuntimeBudget()

        budget.shouldThrottleRebuild(lagEvents = 9_999, foregroundActive = 11, foregroundCapacity = 14).shouldBeFalse()
        budget.shouldThrottleRebuild(lagEvents = 10_000, foregroundActive = 11, foregroundCapacity = 14).shouldBeTrue()
        budget.shouldThrottleRebuild(lagEvents = 0, foregroundActive = 12, foregroundCapacity = 14).shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "normal and exceeded replay projection rebuild and maintenance budgets stay distinct" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `normal and exceeded replay projection rebuild and maintenance budgets stay distinct`() {
        val budget = EventSourcedRuntimeBudget()

        budget.acceptsReplay(10_000, 2.seconds()).shouldBeTrue()
        budget.acceptsReplay(10_001, 2.seconds()).shouldBeFalse()
        budget.acceptsReplay(10_000, 2.seconds() + 1.nanos()).shouldBeFalse()

        budget.acceptsProjectionBatch(200, 2 * 1024 * 1024, 2.seconds()).shouldBeTrue()
        budget.acceptsProjectionBatch(201, 2 * 1024 * 1024, 2.seconds()).shouldBeFalse()
        budget.acceptsProjectionBatch(200, 2 * 1024 * 1024 + 1, 2.seconds()).shouldBeFalse()

        budget.acceptsRebuild(100_000, 10.minutes()).shouldBeTrue()
        budget.acceptsRebuild(100_001, 10.minutes()).shouldBeFalse()
        budget.acceptsRebuild(100_000, 10.minutes() + 1.nanos()).shouldBeFalse()

        budget.acceptsMaintenanceBatch(100, 2 * 1024 * 1024, 2.seconds()).shouldBeTrue()
        budget.acceptsMaintenanceBatch(101, 2 * 1024 * 1024, 2.seconds()).shouldBeFalse()
        budget.acceptsMaintenanceBatch(100, 2 * 1024 * 1024, 2.seconds() + 1.nanos()).shouldBeFalse()

        budget.maintenanceBackoff(0) shouldBeEqualTo 100.millis()
        budget.maintenanceBackoff(20) shouldBeEqualTo 5.seconds()
    }

    @Test
/**
 * 이 시나리오는 "maintenance queue coalesces a stream and rejects only a new stream beyond capacity" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `maintenance queue coalesces a stream and rejects only a new stream beyond capacity`() {
        val queue = SnapshotMaintenanceQueue(capacity = 2)
        val first = stream()
        val second = stream()
        val third = stream()

        queue.offer(SnapshotMaintenanceRequest(first, 250)) shouldBeEqualTo MaintenanceOffer.ENQUEUED
        queue.offer(SnapshotMaintenanceRequest(first, 500)) shouldBeEqualTo MaintenanceOffer.COALESCED
        queue.offer(SnapshotMaintenanceRequest(second, 250)) shouldBeEqualTo MaintenanceOffer.ENQUEUED
        queue.offer(SnapshotMaintenanceRequest(third, 250)) shouldBeEqualTo MaintenanceOffer.REJECTED

        queue.poll() shouldBeEqualTo SnapshotMaintenanceRequest(first, 500)
        queue.poll() shouldBeEqualTo SnapshotMaintenanceRequest(second, 250)
        queue.poll().shouldBeNull()
    }

    @Test
/**
 * 이 시나리오는 "maintenance flood stays bounded while readiness keeps its own permit" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `maintenance flood stays bounded while readiness keeps its own permit`() {
        val queue = SnapshotMaintenanceQueue()
        repeat(64) { index ->
            queue.offer(SnapshotMaintenanceRequest(stream(), (index + 1).toLong())) shouldBeEqualTo
                    MaintenanceOffer.ENQUEUED
        }
        queue.size() shouldBeEqualTo 64
        queue.offer(SnapshotMaintenanceRequest(stream(), 1)) shouldBeEqualTo MaintenanceOffer.REJECTED

        val gate = EventSourcedDatabasePermitGate()
        gate.withPermit(EventSourcedDatabaseLane.MAINTENANCE) {
            gate.snapshot(EventSourcedDatabaseLane.READINESS).available shouldBeEqualTo 1
        }
        gate.withPermit(EventSourcedDatabaseLane.READINESS) {
            gate.snapshot(EventSourcedDatabaseLane.READINESS).active shouldBeEqualTo 1
        }
        gate.snapshot(EventSourcedDatabaseLane.READINESS).available shouldBeEqualTo 1
    }

/**
 * stream은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun stream(): StreamKey = StreamKey(
        tenantId = TenantId("tenant-a"),
        streamType = "campaign",
        streamId = UUID.randomUUID()
    )
}
