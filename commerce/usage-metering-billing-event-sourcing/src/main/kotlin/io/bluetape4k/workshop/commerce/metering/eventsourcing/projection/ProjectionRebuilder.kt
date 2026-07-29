package io.bluetape4k.workshop.commerce.metering.eventsourcing.projection

import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventStore
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionCheckpointRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ProjectionRebuilder(
    private val generations: ProjectionGenerationRepository,
    private val checkpoints: ProjectionCheckpointRepository,
    private val metrics: ProjectionTelemetry? = null,
    private val eventStore: EventStore? = null,
) {
    fun beginNext(projectionName: String, now: Instant): ProjectionGeneration {
        val highWatermark = checkNotNull(eventStore) { "event_store_required" }.latestGlobalPosition()
        return generations.createNextBuilding(projectionName, highWatermark, now).also {
            metrics?.recordRebuild("started")
        }
    }

    fun begin(projectionName: String, nextGeneration: Int, startHighWatermark: Long, now: Instant) {
        val active = checkNotNull(generations.active(projectionName)) { "active_projection_missing:$projectionName" }
        require(nextGeneration > active.generation) { "projection_generation_must_increase" }
        generations.createBuilding(projectionName, nextGeneration, startHighWatermark, now)
        metrics?.recordRebuild("started")
    }

    fun catchUpAndSwitch(
        lease: ProjectionLease,
        expectedActiveGeneration: Int,
        observedHighWatermark: Long,
        now: Instant,
    ): Boolean {
        checkpoints.raiseHighWatermark(lease, observedHighWatermark, now)
        return generations.switchActive(lease, expectedActiveGeneration, now).also { switched ->
            metrics?.recordRebuild(if (switched) "switched" else "pending")
        }
    }
}
