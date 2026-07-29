package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll

internal fun findInProgressGeneration(projection: String): ProjectionGeneration? =
    ProjectionGenerations
        .selectAll()
        .where {
            (ProjectionGenerations.projection eq projection) and
                (ProjectionGenerations.state inList IN_PROGRESS_GENERATION_STATES)
        }.orderBy(ProjectionGenerations.generation to SortOrder.ASC)
        .limit(1)
        .singleOrNull()
        ?.let { row ->
            findGeneration(
                ProjectionKey(
                    row[ProjectionGenerations.projection],
                    row[ProjectionGenerations.generation],
                ),
            )
        }

private val IN_PROGRESS_GENERATION_STATES =
    listOf(
        ProjectionGenerationState.BUILDING,
        ProjectionGenerationState.VALIDATING,
        ProjectionGenerationState.CANCELLING,
    )
