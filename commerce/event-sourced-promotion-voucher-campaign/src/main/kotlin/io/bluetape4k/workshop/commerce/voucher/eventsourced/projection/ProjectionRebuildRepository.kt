package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serializable
import java.time.Instant

internal const val FIRST_REBUILD_GENERATION = 1L
internal const val FIRST_REBUILD_FENCING_TOKEN = 1L
internal const val FIRST_ACTIVE_POINTER_REVISION = 1L

internal enum class ProjectionGenerationState {
    BUILDING,
    VALIDATING,
    ACTIVE,
    CANCELLING,
    CANCELLED,
    FAILED,
    RETIRED,
}

@ConsistentCopyVisibility
internal data class ProjectionGeneration private constructor(
    val key: ProjectionKey,
    val state: ProjectionGenerationState,
    val progress: ProjectionGenerationProgress,
    val canonicalDigest: ReceiptDigest?,
    val retryableFailure: Boolean,
) : Serializable {
    val targetPosition: Long get() = progress.targetPosition

    val currentPosition: Long get() = progress.currentPosition

    val fencingToken: Long get() = progress.fencingToken

    val cancellationRevision: Long get() = progress.cancellationRevision

    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(
            key: ProjectionKey,
            state: ProjectionGenerationState,
            progress: ProjectionGenerationProgress,
            canonicalDigest: ReceiptDigest?,
            retryableFailure: Boolean,
        ): ProjectionGeneration = ProjectionGeneration(key, state, progress, canonicalDigest, retryableFailure)
    }
}

@ConsistentCopyVisibility
internal data class ProjectionGenerationProgress private constructor(
    val targetPosition: Long,
    val currentPosition: Long,
    val fencingToken: Long,
    val cancellationRevision: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(
            targetPosition: Long,
            currentPosition: Long,
            fencingToken: Long,
            cancellationRevision: Long,
        ): ProjectionGenerationProgress {
            require(targetPosition >= currentPosition) { "target position must not precede current position" }
            require(fencingToken > 0) { "fencing token must be positive" }
            require(cancellationRevision >= 0) { "cancellation revision must be non-negative" }
            return ProjectionGenerationProgress(targetPosition, currentPosition, fencingToken, cancellationRevision)
        }
    }
}

@ConsistentCopyVisibility
internal data class ActiveProjectionGeneration private constructor(
    val projection: String,
    val generation: Long,
    val revision: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(
            projection: String,
            generation: Long,
            revision: Long,
        ): ActiveProjectionGeneration {
            return ActiveProjectionGeneration(
                projection = projection.requireNotBlank("projection"),
                generation = generation.also { require(it > 0) { "generation must be positive" } },
                revision = revision.also { require(it > 0) { "revision must be positive" } },
            )
        }
    }
}

@ConsistentCopyVisibility
internal data class ProjectionRebuildCursor private constructor(
    val fencingToken: Long,
    val cancellationRevision: Long,
    val expectedPosition: Long,
    val position: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(
            fencingToken: Long,
            cancellationRevision: Long,
            expectedPosition: Long,
            position: Long,
        ): ProjectionRebuildCursor {
            require(fencingToken > 0) { "fencing token must be positive" }
            require(cancellationRevision >= 0) { "cancellation revision must be non-negative" }
            require(expectedPosition >= 0) { "expected position must be non-negative" }
            require(position > expectedPosition) { "position must advance the generation cursor" }
            return ProjectionRebuildCursor(fencingToken, cancellationRevision, expectedPosition, position)
        }
    }
}

internal sealed interface ProjectionActivationResult {
    data object Activated : ProjectionActivationResult

    data object StalePointer : ProjectionActivationResult

    data object CandidateNotReady : ProjectionActivationResult
}

/**
 * Durable generation authority. The active pointer changes only after a candidate has reached a
 * validated target head; all caller mutations run in the foreground PostgreSQL transaction.
 */
internal class ProjectionRebuildRepository {

    fun initializeActive(
        projection: String,
        now: Instant,
    ): ActiveProjectionGeneration {
        TransactionManager.current()
        projection.requireNotBlank("projection")
        val inserted =
            ActiveProjectionGenerations.insertIgnore { row ->
                row[ActiveProjectionGenerations.projection] = projection
                row[ActiveProjectionGenerations.generation] = FIRST_REBUILD_GENERATION
                row[ActiveProjectionGenerations.revision] = FIRST_ACTIVE_POINTER_REVISION
                row[ActiveProjectionGenerations.updatedAt] = now
            }.insertedCount == 1
        if (inserted) {
            insertInitialProjectionGeneration(projection, now)
        }
        return requireNotNull(findActive(projection)) { "active projection pointer disappeared" }
    }

    fun start(
        projection: String,
        targetPosition: Long,
        now: Instant,
    ): ProjectionGeneration {
        TransactionManager.current()
        require(targetPosition >= 0) { "target position must be non-negative" }
        requireLockedActive(projection)
        require(findBuildingGeneration(projection) == null) { "projection already has a building generation" }
        val generation = latestProjectionGeneration(projection) + 1
        ProjectionGenerations.insert { row ->
            row[ProjectionGenerations.projection] = projection
            row[ProjectionGenerations.generation] = generation
            row[ProjectionGenerations.state] = ProjectionGenerationState.BUILDING
            row[ProjectionGenerations.targetPosition] = targetPosition
            row[ProjectionGenerations.currentPosition] = 0
            row[ProjectionGenerations.fencingToken] = FIRST_REBUILD_FENCING_TOKEN
            row[ProjectionGenerations.cancellationRevision] = 0
            row[ProjectionGenerations.canonicalDigest] = null
            row[ProjectionGenerations.retryableFailure] = false
            row[ProjectionGenerations.createdAt] = now
            row[ProjectionGenerations.updatedAt] = now
        }
        val key = ProjectionKey(projection, generation)
        return requireNotNull(findGeneration(key)) { "building generation was not persisted" }
    }

    fun advance(
        key: ProjectionKey,
        cursor: ProjectionRebuildCursor,
        now: Instant,
    ): Boolean {
        TransactionManager.current()
        return ProjectionGenerations.update(
            where = {
                generationPredicate(key) and
                    (ProjectionGenerations.state eq ProjectionGenerationState.BUILDING) and
                    (ProjectionGenerations.fencingToken eq cursor.fencingToken) and
                    (ProjectionGenerations.cancellationRevision eq cursor.cancellationRevision) and
                    (ProjectionGenerations.currentPosition eq cursor.expectedPosition)
            },
        ) { row ->
            row[ProjectionGenerations.currentPosition] = cursor.position
            row[ProjectionGenerations.updatedAt] = now
        } == 1
    }

    fun beginValidation(
        key: ProjectionKey,
        fencingToken: Long,
        cancellationRevision: Long,
        canonicalDigest: ReceiptDigest,
        now: Instant,
    ): Boolean {
        TransactionManager.current()
        require(cancellationRevision >= 0) { "cancellation revision must be non-negative" }
        return ProjectionGenerations.update(
            where = {
                generationPredicate(key) and
                    (ProjectionGenerations.state eq ProjectionGenerationState.BUILDING) and
                    (ProjectionGenerations.fencingToken eq fencingToken) and
                    (ProjectionGenerations.cancellationRevision eq cancellationRevision) and
                    (ProjectionGenerations.currentPosition eq ProjectionGenerations.targetPosition)
            },
        ) { row ->
            row[ProjectionGenerations.state] = ProjectionGenerationState.VALIDATING
            row[ProjectionGenerations.canonicalDigest] = canonicalDigest.value
            row[ProjectionGenerations.updatedAt] = now
        } == 1
    }

    fun activate(
        key: ProjectionKey,
        expectedPointerRevision: Long,
        targetHead: Long,
        canonicalDigest: ReceiptDigest,
        now: Instant,
    ): ProjectionActivationResult {
        TransactionManager.current()
        val pointer = requireLockedActive(key.projection)
        val candidate = requireNotNull(lockProjectionGeneration(key)) { "candidate generation disappeared" }
        return when {
            pointer.revision != expectedPointerRevision -> ProjectionActivationResult.StalePointer
            candidate.state != ProjectionGenerationState.VALIDATING ||
                candidate.targetPosition != targetHead ||
                candidate.currentPosition != targetHead ||
                candidate.canonicalDigest != canonicalDigest ->
                ProjectionActivationResult.CandidateNotReady

            else -> {
                retirePreviousProjectionGeneration(pointer, now)
                updateActiveProjectionPointer(key, pointer, now)
                ProjectionGenerations.update(where = { generationPredicate(key) }) { row ->
                    row[ProjectionGenerations.state] = ProjectionGenerationState.ACTIVE
                    row[ProjectionGenerations.updatedAt] = now
                }
                ProjectionActivationResult.Activated
            }
        }
    }

    fun requestCancellation(
        key: ProjectionKey,
        now: Instant,
    ): ProjectionGeneration? {
        TransactionManager.current()
        return lockProjectionGeneration(key)?.let { current ->
            if (current.state !in CANCELLABLE_STATES) {
                current
            } else {
                ProjectionGenerations.update(where = { generationPredicate(key) }) { row ->
                    row[ProjectionGenerations.state] = ProjectionGenerationState.CANCELLING
                    row[ProjectionGenerations.fencingToken] = current.fencingToken + 1
                    row[ProjectionGenerations.cancellationRevision] = current.cancellationRevision + 1
                    row[ProjectionGenerations.updatedAt] = now
                }
                requireNotNull(findGeneration(key)) { "cancelling generation disappeared" }
            }
        }
    }

    fun completeCancellation(
        key: ProjectionKey,
        fencingToken: Long,
        now: Instant,
    ): Boolean {
        TransactionManager.current()
        return ProjectionGenerations.update(
            where = {
                generationPredicate(key) and
                    (ProjectionGenerations.state eq ProjectionGenerationState.CANCELLING) and
                    (ProjectionGenerations.fencingToken eq fencingToken)
            },
        ) { row ->
            row[ProjectionGenerations.state] = ProjectionGenerationState.CANCELLED
            row[ProjectionGenerations.updatedAt] = now
        } == 1
    }

    fun fail(
        key: ProjectionKey,
        fencingToken: Long,
        retryable: Boolean,
        now: Instant,
    ): Boolean {
        TransactionManager.current()
        return ProjectionGenerations.update(
            where = {
                generationPredicate(key) and
                    (ProjectionGenerations.state eq ProjectionGenerationState.BUILDING) and
                    (ProjectionGenerations.fencingToken eq fencingToken)
            },
        ) { row ->
            row[ProjectionGenerations.state] = ProjectionGenerationState.FAILED
            row[ProjectionGenerations.retryableFailure] = retryable
            row[ProjectionGenerations.updatedAt] = now
        } == 1
    }

    fun resume(
        key: ProjectionKey,
        now: Instant,
    ): ProjectionGeneration? {
        TransactionManager.current()
        return lockProjectionGeneration(key)?.let { current ->
            if (current.state != ProjectionGenerationState.FAILED || !current.retryableFailure) {
                current
            } else {
                ProjectionGenerations.update(where = { generationPredicate(key) }) { row ->
                    row[ProjectionGenerations.state] = ProjectionGenerationState.BUILDING
                    row[ProjectionGenerations.fencingToken] = current.fencingToken + 1
                    row[ProjectionGenerations.updatedAt] = now
                }
                requireNotNull(findGeneration(key)) { "resumed generation disappeared" }
            }
        }
    }

}
