package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

internal val CANCELLABLE_STATES = setOf(ProjectionGenerationState.BUILDING, ProjectionGenerationState.VALIDATING)

internal fun generationPredicate(key: ProjectionKey) =
    (ProjectionGenerations.projection eq key.projection) and
        (ProjectionGenerations.generation eq key.generation)

internal fun findActive(projection: String): ActiveProjectionGeneration? =
    ActiveProjectionGenerations
        .selectAll()
        .where { ActiveProjectionGenerations.projection eq projection }
        .singleOrNull()
        ?.let(::toActiveGeneration)

internal fun requireLockedActive(projection: String): ActiveProjectionGeneration =
    checkNotNull(
        ActiveProjectionGenerations
            .selectAll()
            .where { ActiveProjectionGenerations.projection eq projection }
            .forUpdate()
            .singleOrNull()
            ?.let(::toActiveGeneration),
    ) { "active projection must be initialized" }

internal fun findGeneration(key: ProjectionKey): ProjectionGeneration? =
    ProjectionGenerations
        .selectAll()
        .where { generationPredicate(key) }
        .singleOrNull()
        ?.let(::toGeneration)

internal fun lockProjectionGeneration(key: ProjectionKey): ProjectionGeneration? =
    ProjectionGenerations
        .selectAll()
        .where { generationPredicate(key) }
        .forUpdate()
        .singleOrNull()
        ?.let(::toGeneration)

internal fun lockBuildingGeneration(
    key: ProjectionKey,
    fencingToken: Long,
    cancellationRevision: Long,
): ProjectionGeneration? =
    lockProjectionGeneration(key)?.takeIf { generation ->
        generation.state == ProjectionGenerationState.BUILDING &&
            generation.fencingToken == fencingToken &&
            generation.cancellationRevision == cancellationRevision
    }

internal fun findBuildingGeneration(projection: String): ProjectionGeneration? =
    ProjectionGenerations
        .selectAll()
        .where {
            (ProjectionGenerations.projection eq projection) and
                (ProjectionGenerations.state eq ProjectionGenerationState.BUILDING)
        }.singleOrNull()
        ?.let(::toGeneration)

internal fun latestProjectionGeneration(projection: String): Long =
    ProjectionGenerations
        .selectAll()
        .where { ProjectionGenerations.projection eq projection }
        .orderBy(ProjectionGenerations.generation to SortOrder.DESC)
        .limit(1)
        .single()[ProjectionGenerations.generation]

private fun toActiveGeneration(row: ResultRow): ActiveProjectionGeneration =
    ActiveProjectionGeneration(
        projection = row[ActiveProjectionGenerations.projection],
        generation = row[ActiveProjectionGenerations.generation],
        revision = row[ActiveProjectionGenerations.revision],
    )

private fun toGeneration(row: ResultRow): ProjectionGeneration =
    ProjectionGeneration(
        key = ProjectionKey(row[ProjectionGenerations.projection], row[ProjectionGenerations.generation]),
        state = row[ProjectionGenerations.state],
        progress =
            ProjectionGenerationProgress(
                targetPosition = row[ProjectionGenerations.targetPosition],
                currentPosition = row[ProjectionGenerations.currentPosition],
                fencingToken = row[ProjectionGenerations.fencingToken],
                cancellationRevision = row[ProjectionGenerations.cancellationRevision],
            ),
        canonicalDigest = row[ProjectionGenerations.canonicalDigest]?.let(ReceiptDigest::of),
        retryableFailure = row[ProjectionGenerations.retryableFailure],
    )
