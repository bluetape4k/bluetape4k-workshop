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
import org.junit.jupiter.api.Test
import java.util.*

internal class EventSourcedPerformancePolicyTest {

    companion object: KLogging()

    @Test
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
    fun `rebuild throttle reacts only at lag or foreground saturation boundaries`() {
        val budget = EventSourcedRuntimeBudget()

        budget.shouldThrottleRebuild(lagEvents = 9_999, foregroundActive = 11, foregroundCapacity = 14).shouldBeFalse()
        budget.shouldThrottleRebuild(lagEvents = 10_000, foregroundActive = 11, foregroundCapacity = 14).shouldBeTrue()
        budget.shouldThrottleRebuild(lagEvents = 0, foregroundActive = 12, foregroundCapacity = 14).shouldBeTrue()
    }

    @Test
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

    private fun stream(): StreamKey = StreamKey(
        tenantId = TenantId("tenant-a"),
        streamType = "campaign",
        streamId = UUID.randomUUID()
    )
}
