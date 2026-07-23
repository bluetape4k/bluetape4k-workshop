package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

internal const val MAX_REBUILD_RETENTION_BATCH_SIZE = 100

internal class ProjectionRebuildMaintenance {
    fun recover(
        key: ProjectionKey,
        now: Instant,
    ): ProjectionGeneration? {
        TransactionManager.current()
        return lockProjectionGeneration(key)?.let { current ->
            if (current.state != ProjectionGenerationState.CANCELLING) {
                current
            } else {
                ProjectionGenerations.update(
                    where = {
                        generationPredicate(key) and
                            (ProjectionGenerations.state eq ProjectionGenerationState.CANCELLING) and
                            (ProjectionGenerations.fencingToken eq current.fencingToken)
                    },
                ) { row ->
                    row[ProjectionGenerations.state] = ProjectionGenerationState.CANCELLED
                    row[ProjectionGenerations.updatedAt] = now
                }
                requireNotNull(findGeneration(key)) { "recovered generation disappeared" }
            }
        }
    }

    fun purgeRetired(
        projection: String,
        retiredBefore: Instant,
        batchSize: Int,
    ): Int {
        TransactionManager.current()
        projection.requireNotBlank("projection")
        require(batchSize in 1..MAX_REBUILD_RETENTION_BATCH_SIZE) {
            "batch size must be between 1 and $MAX_REBUILD_RETENTION_BATCH_SIZE"
        }
        return ProjectionGenerations
            .selectAll()
            .where {
                (ProjectionGenerations.projection eq projection) and
                    (ProjectionGenerations.state eq ProjectionGenerationState.RETIRED) and
                    (ProjectionGenerations.updatedAt less retiredBefore)
            }
            .orderBy(ProjectionGenerations.generation to SortOrder.ASC)
            .limit(batchSize)
            .map { row ->
                ProjectionKey(row[ProjectionGenerations.projection], row[ProjectionGenerations.generation])
            }.sumOf { retiredKey ->
                ProjectionGenerations.deleteWhere { generationPredicate(retiredKey) }
            }
    }
}
