package io.bluetape4k.workshop.commerce.metering.eventsourcing.worker

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionCheckpointRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionFailureRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.NewProjectionFailure
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGeneration
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGenerationState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionLease
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ProjectionWorkerDigestTest {

    @Test
    fun `quarantine stores deterministic digest of failure type and message`() {
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val lease = ProjectionLease(
            projectionName = "billing",
            generation = 1,
            ownerToken = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            leaseUntil = now.plusSeconds(30),
        )
        val persisted = PersistedEvent(
            eventId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            stream = StreamKey("tenant-a", "Usage", "usage-1"),
            streamVersion = 1,
            globalPosition = 1,
            eventType = "usage.accepted",
            schemaVersion = 1,
            payload = "{}",
            metadata = "{}",
            previousHash = null,
            eventHash = "a".repeat(64),
            occurredAt = now,
            recordedAt = now,
        )
        val eventStore = mockk<EventStoreRepository>()
        val checkpoints = mockk<ProjectionCheckpointRepository>()
        val failures = mockk<ProjectionFailureRepository>()
        val recordedFailure = slot<NewProjectionFailure>()
        every { checkpoints.requireOwnership(lease) } returns ProjectionGeneration(
            projectionName = lease.projectionName,
            generation = lease.generation,
            state = ProjectionGenerationState.ACTIVE,
            checkpoint = 0,
            highWatermark = 1,
            ownerToken = lease.ownerToken,
            leaseUntil = lease.leaseUntil,
            failedPosition = null,
            failureDigest = null,
        )
        every { eventStore.loadAfterGlobalPosition(0, 1) } returns listOf(persisted)
        every { failures.record(capture(recordedFailure)) } just runs
        every { checkpoints.markFailed(any(), any(), any(), any()) } just runs
        val processor = ProjectionBatchProcessor(
            eventStore = eventStore,
            checkpoints = checkpoints,
            failures = failures,
            applier = mockk<ProjectionEventApplier>(relaxed = true),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        processor.quarantine(lease, IllegalStateException("fixture failure")) shouldBeEqualTo "usage.accepted"

        val expectedDigest = "70e1afa66135205173e0b2c8a9a36a766c1e13fd0e18c1d09ed68a7aca172bb6"
        recordedFailure.captured.errorDigest shouldBeEqualTo expectedDigest
        verify(exactly = 1) { checkpoints.markFailed(lease, 1, expectedDigest, now) }
    }
}
