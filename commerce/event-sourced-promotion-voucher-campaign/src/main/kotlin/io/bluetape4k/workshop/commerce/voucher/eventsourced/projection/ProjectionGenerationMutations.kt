package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

internal fun retirePreviousProjectionGeneration(
    pointer: ActiveProjectionGeneration,
    now: Instant,
) {
    val previous = ProjectionKey(pointer.projection, pointer.generation)
    ProjectionGenerations.update(where = { generationPredicate(previous) }) { row ->
        row[ProjectionGenerations.state] = ProjectionGenerationState.RETIRED
        row[ProjectionGenerations.updatedAt] = now
    }
}

internal fun insertInitialProjectionGeneration(
    projection: String,
    now: Instant,
) {
    ProjectionGenerations.insertIgnore { row ->
        row[ProjectionGenerations.projection] = projection
        row[ProjectionGenerations.generation] = FIRST_REBUILD_GENERATION
        row[ProjectionGenerations.state] = ProjectionGenerationState.ACTIVE
        row[ProjectionGenerations.targetPosition] = 0
        row[ProjectionGenerations.currentPosition] = 0
        row[ProjectionGenerations.fencingToken] = FIRST_REBUILD_FENCING_TOKEN
        row[ProjectionGenerations.cancellationRevision] = 0
        row[ProjectionGenerations.canonicalDigest] = null
        row[ProjectionGenerations.retryableFailure] = false
        row[ProjectionGenerations.createdAt] = now
        row[ProjectionGenerations.updatedAt] = now
    }
}

internal fun updateActiveProjectionPointer(
    key: ProjectionKey,
    pointer: ActiveProjectionGeneration,
    now: Instant,
) {
    ActiveProjectionGenerations.update(where = { ActiveProjectionGenerations.projection eq key.projection }) { row ->
        row[ActiveProjectionGenerations.generation] = key.generation
        row[ActiveProjectionGenerations.revision] = pointer.revision + 1
        row[ActiveProjectionGenerations.updatedAt] = now
    }
}
