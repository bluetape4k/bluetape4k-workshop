package io.bluetape4k.workshop.commerce.metering.eventsourcing.projection

import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionCheckpointRepository
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

@Component
class ProjectionCoordinator(
    private val repository: ProjectionCheckpointRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun apply(
        lease: ProjectionLease,
        eventId: UUID,
        globalPosition: Long,
        handler: () -> Unit,
    ): ProjectionApplyResult {
        repository.requireOwnership(lease)
        val inserted = repository.insertAppliedEvent(lease, eventId, globalPosition, clock.instant())
        if (inserted) handler()
        repository.advanceCheckpoint(lease, globalPosition, clock.instant())
        return if (inserted) ProjectionApplyResult.APPLIED else ProjectionApplyResult.ALREADY_APPLIED
    }
}
