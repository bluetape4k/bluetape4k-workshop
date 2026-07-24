package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionCheckpoints
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionPoisonEvents
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionProcessedEvents
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionReadModels
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

internal const val MAX_PROJECTION_BATCH_EVENTS = 200
internal const val MAX_PROJECTION_POISON_ATTEMPTS = 5
private const val KIBIBYTE = 1024
internal const val MAX_PROJECTION_BATCH_BYTES = 2 * KIBIBYTE * KIBIBYTE

internal data class ProjectionKey(
    val projection: String,
    val generation: Long,
) {
    init {
        projection.requireNotBlank("projection")
        generation.requirePositiveNumber("generation")
    }
}

internal data class ProjectionCheckpoint(
    val position: Long,
    val fencingToken: Long,
    val updatedAt: Instant,
)

internal data class ProjectionApplyResult(
    val appliedEventCount: Int,
    val duplicateEventCount: Int,
    val checkpoint: ProjectionCheckpoint,
)

internal data class ProjectionReadModel(
    val tenantId: String,
    val streamType: String,
    val streamId: UUID,
    val streamVersion: Long,
    val globalPosition: Long,
    val eventType: String,
    val payloadDigest: String,
    val fencingToken: Long,
)

internal data class ProjectionPoisonRecord(
    val eventId: UUID,
    val globalPosition: Long,
    val reasonClass: String,
    val attempts: Int,
    val state: ProjectionPoisonState,
    val nextRetryAt: Instant,
    val resolvedAt: Instant?,
)

internal enum class ProjectionPoisonState {
    FAILED,
    RESOLVED,
}

/**
 * Projection state remains disposable: event identity, read-model mutation, and checkpoint advance
 * commit together only while the current fenced owner holds the PostgreSQL lease row lock.
 */
internal class ProjectionRepository(
    private val leases: ProjectionLeaseRepository,
    private val campaigns: CampaignProjectionStore = CampaignProjectionStore(),
    private val poisons: ProjectionPoisonStore = ProjectionPoisonStore(),
) {

    fun applyBatch(
        key: ProjectionKey,
        lease: ProjectionLease,
        events: List<EventEnvelope>,
        now: Instant,
    ): ProjectionApplyResult {
        TransactionManager.current()
        validateBatch(events)
        leases.requireActive(key.projection, key.generation, lease, now)
        val result =
            events.fold(ProjectionMutationCount()) { count, event ->
                applyEvent(key, lease, event, now, count)
            }
        val checkpoint = advanceCheckpoint(key, lease, events, now)
        return ProjectionApplyResult(result.applied, result.duplicates, checkpoint)
    }

    fun checkpoint(key: ProjectionKey): ProjectionCheckpoint? {
        TransactionManager.current()
        return ProjectionCheckpoints
            .selectAll()
            .where { checkpointPredicate(key) }
            .singleOrNull()
            ?.let(::toCheckpoint)
    }

    fun readModel(
        key: ProjectionKey,
        streamType: String,
        streamId: UUID,
    ): ProjectionReadModel? {
        TransactionManager.current()
        return ProjectionReadModels
            .selectAll()
            .where {
                keyPredicate(ProjectionReadModels.projection, ProjectionReadModels.generation, key) and
                    (ProjectionReadModels.streamType eq streamType) and
                    (ProjectionReadModels.streamId eq streamId)
            }.singleOrNull()
            ?.let(::toReadModel)
    }

    fun campaign(
        key: ProjectionKey,
        tenantId: TenantId,
        campaignId: UUID,
    ): CampaignProjectionReadModel? {
        TransactionManager.current()
        return campaigns.find(key, tenantId, campaignId)
    }

    fun poison(
        key: ProjectionKey,
        lease: ProjectionLease,
        event: EventEnvelope,
        reasonClass: String,
        now: Instant,
    ): ProjectionPoisonRecord {
        TransactionManager.current()
        val validReasonClass = reasonClass.requireNotBlank("reasonClass")
        leases.requireActive(key.projection, key.generation, lease, now)
        return poisons.recordFailure(key, event, validReasonClass, now)
    }

    fun resolvePoison(
        key: ProjectionKey,
        lease: ProjectionLease,
        event: EventEnvelope,
        now: Instant,
    ): ProjectionApplyResult {
        TransactionManager.current()
        val poison = checkNotNull(poisons.find(key, event.eventId)) { "poison event does not exist" }
        check(poison.state == ProjectionPoisonState.FAILED) { "poison event is already resolved" }
        check(poison.attempts <= MAX_PROJECTION_POISON_ATTEMPTS) { "poison retry limit is exhausted" }
        check(!now.isBefore(poison.nextRetryAt)) { "poison retry backoff has not elapsed" }
        val result = applyBatch(key, lease, listOf(event), now)
        poisons.resolve(key, event.eventId, now)
        return result
    }

    private fun applyEvent(
        key: ProjectionKey,
        lease: ProjectionLease,
        event: EventEnvelope,
        now: Instant,
        count: ProjectionMutationCount,
    ): ProjectionMutationCount {
        val inserted =
            ProjectionProcessedEvents.insertIgnore { row ->
                row[ProjectionProcessedEvents.projection] = key.projection
                row[ProjectionProcessedEvents.generation] = key.generation
                row[ProjectionProcessedEvents.eventId] = event.eventId
                row[ProjectionProcessedEvents.globalPosition] = event.globalPosition
                row[ProjectionProcessedEvents.processedAt] = now
            }.insertedCount == 1
        if (!inserted) {
            return count.duplicate()
        }
        upsertReadModel(key, lease, event, now)
        campaigns.apply(key, lease, event, now)
        return count.applied()
    }

    private fun upsertReadModel(
        key: ProjectionKey,
        lease: ProjectionLease,
        event: EventEnvelope,
        now: Instant,
    ) {
        ProjectionReadModels.insertIgnore { row ->
            row[ProjectionReadModels.projection] = key.projection
            row[ProjectionReadModels.generation] = key.generation
            row[ProjectionReadModels.tenantId] = event.tenantId.value
            row[ProjectionReadModels.streamType] = event.stream.type
            row[ProjectionReadModels.streamId] = event.stream.id
            row[ProjectionReadModels.streamVersion] = event.stream.version
            row[ProjectionReadModels.globalPosition] = event.globalPosition
            row[ProjectionReadModels.eventType] = event.eventType
            row[ProjectionReadModels.payloadDigest] = event.canonicalChecksum
            row[ProjectionReadModels.fencingToken] = lease.fencingToken
            row[ProjectionReadModels.updatedAt] = now
        }
        ProjectionReadModels.update(
            where = {
                keyPredicate(ProjectionReadModels.projection, ProjectionReadModels.generation, key) and
                    (ProjectionReadModels.tenantId eq event.tenantId.value) and
                    (ProjectionReadModels.streamType eq event.stream.type) and
                    (ProjectionReadModels.streamId eq event.stream.id) and
                    (ProjectionReadModels.streamVersion less event.stream.version) and
                    (ProjectionReadModels.fencingToken lessEq lease.fencingToken)
            },
        ) { row ->
            row[ProjectionReadModels.streamVersion] = event.stream.version
            row[ProjectionReadModels.globalPosition] = event.globalPosition
            row[ProjectionReadModels.eventType] = event.eventType
            row[ProjectionReadModels.payloadDigest] = event.canonicalChecksum
            row[ProjectionReadModels.fencingToken] = lease.fencingToken
            row[ProjectionReadModels.updatedAt] = now
        }
    }

    private fun advanceCheckpoint(
        key: ProjectionKey,
        lease: ProjectionLease,
        events: List<EventEnvelope>,
        now: Instant,
    ): ProjectionCheckpoint {
        val position = events.maxOfOrNull(EventEnvelope::globalPosition) ?: return currentCheckpoint(key, lease, now)
        ProjectionCheckpoints.insertIgnore { row ->
            row[ProjectionCheckpoints.projection] = key.projection
            row[ProjectionCheckpoints.generation] = key.generation
            row[ProjectionCheckpoints.position] = position
            row[ProjectionCheckpoints.fencingToken] = lease.fencingToken
            row[ProjectionCheckpoints.updatedAt] = now
        }
        ProjectionCheckpoints.update(
            where = {
                checkpointPredicate(key) and
                    (ProjectionCheckpoints.position less position) and
                    (ProjectionCheckpoints.fencingToken lessEq lease.fencingToken)
            },
        ) { row ->
            row[ProjectionCheckpoints.position] = position
            row[ProjectionCheckpoints.fencingToken] = lease.fencingToken
            row[ProjectionCheckpoints.updatedAt] = now
        }
        return currentCheckpoint(key, lease, now)
    }

    private fun currentCheckpoint(
        key: ProjectionKey,
        lease: ProjectionLease,
        now: Instant,
    ): ProjectionCheckpoint =
        checkpoint(key)
            ?: ProjectionCheckpoint(position = 0, fencingToken = lease.fencingToken, updatedAt = now)

}

private data class ProjectionMutationCount(
    val applied: Int = 0,
    val duplicates: Int = 0,
) {
    fun applied(): ProjectionMutationCount = copy(applied = applied + 1)

    fun duplicate(): ProjectionMutationCount = copy(duplicates = duplicates + 1)
}

private fun validateBatch(events: List<EventEnvelope>) {
    events.size.requireLe(MAX_PROJECTION_BATCH_EVENTS, "projection batch size")
    events.map(EventEnvelope::eventId).toSet().size.requireEquals(events.size, "uniqueEventIds.size")
    events
        .zipWithNext()
        .all { (current, next) -> current.globalPosition < next.globalPosition }
        .requireEquals(true, "projectionBatch.globallyOrdered")
    val payloadBytes = events.sumOf { event -> event.payload.canonicalJson.toByteArray(StandardCharsets.UTF_8).size }
    payloadBytes.requireLe(MAX_PROJECTION_BATCH_BYTES, "projection batch payload bytes")
}

private fun checkpointPredicate(key: ProjectionKey) =
    keyPredicate(ProjectionCheckpoints.projection, ProjectionCheckpoints.generation, key)

private fun keyPredicate(
    projection: org.jetbrains.exposed.v1.core.Column<String>,
    generation: org.jetbrains.exposed.v1.core.Column<Long>,
    key: ProjectionKey,
) =
    (projection eq key.projection) and (generation eq key.generation)

private fun toCheckpoint(row: ResultRow): ProjectionCheckpoint =
    ProjectionCheckpoint(
        position = row[ProjectionCheckpoints.position],
        fencingToken = row[ProjectionCheckpoints.fencingToken],
        updatedAt = row[ProjectionCheckpoints.updatedAt],
    )

private fun toReadModel(row: ResultRow): ProjectionReadModel =
    ProjectionReadModel(
        tenantId = row[ProjectionReadModels.tenantId],
        streamType = row[ProjectionReadModels.streamType],
        streamId = row[ProjectionReadModels.streamId],
        streamVersion = row[ProjectionReadModels.streamVersion],
        globalPosition = row[ProjectionReadModels.globalPosition],
        eventType = row[ProjectionReadModels.eventType],
        payloadDigest = row[ProjectionReadModels.payloadDigest],
        fencingToken = row[ProjectionReadModels.fencingToken],
    )
