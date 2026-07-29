package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventLog
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionCheckpoints
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionPoisonEvents
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

internal data class ProjectionReconciliationSnapshot(
    val streamPosition: Long,
    val checkpointPosition: Long,
    val lag: Long,
    val failedPoisonCount: Long,
)

internal class ProjectionRecoveryStore {
    fun event(eventId: UUID): EventEnvelope? =
        EventLog
            .selectAll()
            .where { EventLog.id eq eventId }
            .singleOrNull()
            ?.let(::toProjectionEnvelope)

    fun reconciliation(key: ProjectionKey): ProjectionReconciliationSnapshot {
        val streamPosition =
            EventLog
                .selectAll()
                .orderBy(EventLog.globalPosition to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.get(EventLog.globalPosition)
                ?: 0L
        val checkpointPosition =
            ProjectionCheckpoints
                .selectAll()
                .where {
                    (ProjectionCheckpoints.projection eq key.projection) and
                        (ProjectionCheckpoints.generation eq key.generation)
                }.singleOrNull()
                ?.get(ProjectionCheckpoints.position)
                ?: 0L
        val failedPoisonCount =
            ProjectionPoisonEvents
                .selectAll()
                .where {
                    (ProjectionPoisonEvents.projection eq key.projection) and
                        (ProjectionPoisonEvents.generation eq key.generation) and
                        (ProjectionPoisonEvents.state eq ProjectionPoisonState.FAILED)
                }.count()
        return ProjectionReconciliationSnapshot(
            streamPosition = streamPosition,
            checkpointPosition = checkpointPosition,
            lag = (streamPosition - checkpointPosition).coerceAtLeast(0),
            failedPoisonCount = failedPoisonCount,
        )
    }
}
