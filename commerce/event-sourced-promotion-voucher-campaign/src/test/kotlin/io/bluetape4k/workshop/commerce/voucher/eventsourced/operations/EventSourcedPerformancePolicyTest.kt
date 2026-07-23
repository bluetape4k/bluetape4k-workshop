package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

@Tag("stress")
internal class EventSourcedPerformancePolicyTest {

    @Test
    fun `runtime budgets preserve replay projection rebuild and maintenance boundaries`() {
        val budget = EventSourcedRuntimeBudget()

        budget.snapshotEveryEvents shouldBeEqualTo 250
        budget.maxReplayEvents shouldBeEqualTo 10_000
        budget.maxReplayDuration shouldBeEqualTo Duration.ofSeconds(2)
        budget.projectionBatchEvents shouldBeEqualTo 200
        budget.projectionBatchBytes shouldBeEqualTo 2 * 1024 * 1024
        budget.projectionTransactionTimeout shouldBeEqualTo Duration.ofSeconds(2)
        budget.rebuildBatchEvents shouldBeEqualTo 200
        budget.rebuildMaxEvents shouldBeEqualTo 100_000
        budget.rebuildMaxDuration shouldBeEqualTo Duration.ofMinutes(10)
        budget.maintenanceQueueCapacity shouldBeEqualTo 64
        budget.maintenanceBatchRows shouldBeEqualTo 100
        budget.maintenanceBatchBytes shouldBeEqualTo 2 * 1024 * 1024
        budget.maintenanceTransactionTimeout shouldBeEqualTo Duration.ofSeconds(2)
        budget.maintenanceMinBackoff shouldBeEqualTo Duration.ofMillis(100)
        budget.maintenanceMaxBackoff shouldBeEqualTo Duration.ofSeconds(5)
    }

    @Test
    fun `rebuild throttle reacts only at lag or foreground saturation boundaries`() {
        val budget = EventSourcedRuntimeBudget()

        budget.shouldThrottleRebuild(lagEvents = 9_999, foregroundActive = 11, foregroundCapacity = 14)
            .shouldBeFalse()
        budget.shouldThrottleRebuild(lagEvents = 10_000, foregroundActive = 11, foregroundCapacity = 14)
            .shouldBeTrue()
        budget.shouldThrottleRebuild(lagEvents = 0, foregroundActive = 12, foregroundCapacity = 14)
            .shouldBeTrue()
    }

    @Test
    fun `normal and exceeded replay projection rebuild and maintenance budgets stay distinct`() {
        val budget = EventSourcedRuntimeBudget()

        budget.acceptsReplay(10_000, Duration.ofSeconds(2)).shouldBeTrue()
        budget.acceptsReplay(10_001, Duration.ofSeconds(2)).shouldBeFalse()
        budget.acceptsReplay(10_000, Duration.ofSeconds(2).plusNanos(1)).shouldBeFalse()

        budget.acceptsProjectionBatch(200, 2 * 1024 * 1024, Duration.ofSeconds(2)).shouldBeTrue()
        budget.acceptsProjectionBatch(201, 2 * 1024 * 1024, Duration.ofSeconds(2)).shouldBeFalse()
        budget.acceptsProjectionBatch(200, 2 * 1024 * 1024 + 1, Duration.ofSeconds(2)).shouldBeFalse()

        budget.acceptsRebuild(100_000, Duration.ofMinutes(10)).shouldBeTrue()
        budget.acceptsRebuild(100_001, Duration.ofMinutes(10)).shouldBeFalse()
        budget.acceptsRebuild(100_000, Duration.ofMinutes(10).plusNanos(1)).shouldBeFalse()

        budget.acceptsMaintenanceBatch(100, 2 * 1024 * 1024, Duration.ofSeconds(2)).shouldBeTrue()
        budget.acceptsMaintenanceBatch(101, 2 * 1024 * 1024, Duration.ofSeconds(2)).shouldBeFalse()
        budget.acceptsMaintenanceBatch(100, 2 * 1024 * 1024, Duration.ofSeconds(2).plusNanos(1))
            .shouldBeFalse()

        budget.maintenanceBackoff(0) shouldBeEqualTo Duration.ofMillis(100)
        budget.maintenanceBackoff(20) shouldBeEqualTo Duration.ofSeconds(5)
    }

    @Test
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
        queue.poll() shouldBeEqualTo null
    }

    @Test
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

    private fun stream(): StreamKey = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())
}
