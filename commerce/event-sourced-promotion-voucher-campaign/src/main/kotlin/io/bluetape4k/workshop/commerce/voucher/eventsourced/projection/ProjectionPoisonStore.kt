package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionPoisonEvents
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

internal class ProjectionPoisonStore {
    fun recordFailure(
        key: ProjectionKey,
        event: EventEnvelope,
        reasonClass: String,
        now: Instant,
    ): ProjectionPoisonRecord {
        val inserted =
            ProjectionPoisonEvents.insertIgnore { row ->
                row[ProjectionPoisonEvents.projection] = key.projection
                row[ProjectionPoisonEvents.generation] = key.generation
                row[ProjectionPoisonEvents.eventId] = event.eventId
                row[ProjectionPoisonEvents.globalPosition] = event.globalPosition
                row[ProjectionPoisonEvents.eventType] = event.eventType
                row[ProjectionPoisonEvents.reasonClass] = reasonClass
                row[ProjectionPoisonEvents.attempts] = FIRST_POISON_ATTEMPT
                row[ProjectionPoisonEvents.state] = ProjectionPoisonState.FAILED
                row[ProjectionPoisonEvents.nextRetryAt] = nextRetryAt(now, FIRST_POISON_ATTEMPT)
                row[ProjectionPoisonEvents.resolvedAt] = null
                row[ProjectionPoisonEvents.occurredAt] = now
                row[ProjectionPoisonEvents.updatedAt] = now
            }.insertedCount == 1
        if (!inserted) {
            incrementFailure(key, event.eventId, reasonClass, now)
        }
        return checkNotNull(find(key, event.eventId)) { "poison event was not persisted" }
    }

    fun find(
        key: ProjectionKey,
        eventId: UUID,
    ): ProjectionPoisonRecord? =
        ProjectionPoisonEvents
            .selectAll()
            .where {
                poisonKey(key) and
                    (ProjectionPoisonEvents.eventId eq eventId)
            }.singleOrNull()
            ?.let(::toPoisonRecord)

    fun resolve(
        key: ProjectionKey,
        eventId: UUID,
        now: Instant,
    ) {
        check(
            ProjectionPoisonEvents.update(
                where = {
                    poisonKey(key) and
                        (ProjectionPoisonEvents.eventId eq eventId) and
                        (ProjectionPoisonEvents.state eq ProjectionPoisonState.FAILED)
                },
            ) { row ->
                row[ProjectionPoisonEvents.state] = ProjectionPoisonState.RESOLVED
                row[ProjectionPoisonEvents.resolvedAt] = now
                row[ProjectionPoisonEvents.updatedAt] = now
            } == 1,
        ) {
            "poison event resolution lost its state fence"
        }
    }

    private fun incrementFailure(
        key: ProjectionKey,
        eventId: UUID,
        reasonClass: String,
        now: Instant,
    ) {
        val current = checkNotNull(find(key, eventId)) { "poison event disappeared" }
        if (current.state != ProjectionPoisonState.FAILED ||
            current.attempts >= MAX_PROJECTION_POISON_ATTEMPTS
        ) {
            return
        }
        val nextAttempt = current.attempts + 1
        ProjectionPoisonEvents.update(
            where = {
                poisonKey(key) and
                    (ProjectionPoisonEvents.eventId eq eventId) and
                    (ProjectionPoisonEvents.attempts eq current.attempts)
            },
        ) { row ->
            row[ProjectionPoisonEvents.attempts] = nextAttempt
            row[ProjectionPoisonEvents.reasonClass] = reasonClass
            row[ProjectionPoisonEvents.nextRetryAt] = nextRetryAt(now, nextAttempt)
            row[ProjectionPoisonEvents.updatedAt] = now
        }
    }
}

private const val FIRST_POISON_ATTEMPT = 1
private const val MAX_POISON_RETRY_BACKOFF_SECONDS = 30L

private fun nextRetryAt(
    now: Instant,
    attempt: Int,
): Instant {
    val delaySeconds = (1L shl (attempt - 1)).coerceAtMost(MAX_POISON_RETRY_BACKOFF_SECONDS)
    return now.plusSeconds(delaySeconds)
}

private fun poisonKey(key: ProjectionKey) =
    (ProjectionPoisonEvents.projection eq key.projection) and
        (ProjectionPoisonEvents.generation eq key.generation)

private fun toPoisonRecord(row: ResultRow): ProjectionPoisonRecord =
    ProjectionPoisonRecord(
        eventId = row[ProjectionPoisonEvents.eventId],
        globalPosition = row[ProjectionPoisonEvents.globalPosition],
        reasonClass = row[ProjectionPoisonEvents.reasonClass],
        attempts = row[ProjectionPoisonEvents.attempts],
        state = row[ProjectionPoisonEvents.state],
        nextRetryAt = row[ProjectionPoisonEvents.nextRetryAt],
        resolvedAt = row[ProjectionPoisonEvents.resolvedAt],
    )
