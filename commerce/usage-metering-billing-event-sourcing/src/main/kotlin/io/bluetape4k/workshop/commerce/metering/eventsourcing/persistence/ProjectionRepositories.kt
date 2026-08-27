package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGeneration
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGenerationState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionLease
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.StaleProjectionOwnerException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Repository
class ProjectionGenerationRepository :
    EventSourcingExposedJdbcRepository<ProjectionGenerationEntity, UUID>(ProjectionGenerationEntity::class.java) {

    fun createInitialActive(projectionName: String, generation: Int, now: Instant) {
        validateProjectionIdentity(projectionName, generation)
        check(ProjectionAliases.insertIgnore {
            it[id] = Uuid.V7.nextId()
            it[ProjectionAliases.projectionName] = projectionName
            it[activeGeneration] = generation
            it[updatedAt] = now
        }.insertedCount == 1) { "active_projection_already_exists:$projectionName" }
        insertGeneration(projectionName, generation, ProjectionGenerationState.ACTIVE, 0, now)
    }

    fun createBuilding(projectionName: String, generation: Int, highWatermark: Long, now: Instant) {
        validateProjectionIdentity(projectionName, generation)
        require(highWatermark >= 0) { "projection_high_watermark_invalid" }
        insertGeneration(projectionName, generation, ProjectionGenerationState.BUILDING, highWatermark, now)
    }

    fun createNextBuilding(projectionName: String, highWatermark: Long, now: Instant): ProjectionGeneration {
        require(highWatermark >= 0) { "projection_high_watermark_invalid" }
        val alias = ProjectionAliases.selectAll()
            .where { ProjectionAliases.projectionName eq projectionName }
            .forUpdate()
            .singleOrNull()
            ?: error("active_projection_missing:$projectionName")
        val latest = ProjectionGenerations.selectAll()
            .where { ProjectionGenerations.projectionName eq projectionName }
            .orderBy(ProjectionGenerations.generation to org.jetbrains.exposed.v1.core.SortOrder.DESC)
            .limit(1)
            .single()
            .toProjectionGeneration()
        check(latest.state != ProjectionGenerationState.BUILDING) { "rebuild_already_in_progress:$projectionName" }
        check(alias[ProjectionAliases.activeGeneration] > 0) { "active_projection_missing:$projectionName" }
        val nextGeneration = latest.generation + 1
        insertGeneration(projectionName, nextGeneration, ProjectionGenerationState.BUILDING, highWatermark, now)
        return checkNotNull(get(projectionName, nextGeneration))
    }

    fun get(projectionName: String, generation: Int): ProjectionGeneration? =
        projectionGenerationRow(projectionName, generation)?.toProjectionGeneration()

    fun active(projectionName: String): ProjectionGeneration? {
        val generation = ProjectionAliases.selectAll()
            .where { ProjectionAliases.projectionName eq projectionName }
            .singleOrNull()
            ?.get(ProjectionAliases.activeGeneration)
        return generation?.let { get(projectionName, it) }
    }

    fun switchActive(lease: ProjectionLease, expectedActiveGeneration: Int, now: Instant): Boolean {
        val candidate = lockedProjectionGenerationRow(lease.projectionName, lease.generation)
            ?.toProjectionGeneration()
            ?: throw StaleProjectionOwnerException(lease.projectionName, lease.generation)
        if (candidate.ownerToken != lease.ownerToken) {
            throw StaleProjectionOwnerException(lease.projectionName, lease.generation)
        }
        val switchable = candidate.state == ProjectionGenerationState.BUILDING &&
            candidate.checkpoint >= candidate.highWatermark
        val alias = ProjectionAliases.selectAll()
            .where { ProjectionAliases.projectionName eq lease.projectionName }
            .forUpdate()
            .singleOrNull()
        val expectedAlias = alias?.get(ProjectionAliases.activeGeneration) == expectedActiveGeneration
        if (!switchable || !expectedAlias) return false
        transition(lease.projectionName, expectedActiveGeneration, ProjectionGenerationState.RETIRED, now)
        transition(lease.projectionName, lease.generation, ProjectionGenerationState.ACTIVE, now)
        updateAlias(lease.projectionName, lease.generation, now)
        return true
    }

    fun rollbackActive(
        projectionName: String,
        expectedCurrentGeneration: Int,
        targetGeneration: Int,
        now: Instant,
    ): Boolean {
        val alias = ProjectionAliases.selectAll()
            .where { ProjectionAliases.projectionName eq projectionName }
            .forUpdate()
            .singleOrNull()
        val currentMatches = alias?.get(ProjectionAliases.activeGeneration) == expectedCurrentGeneration
        val target = lockedProjectionGenerationRow(projectionName, targetGeneration)?.toProjectionGeneration()
        val canRollback = currentMatches && target?.state == ProjectionGenerationState.RETIRED
        if (!canRollback) return false
        transition(projectionName, expectedCurrentGeneration, ProjectionGenerationState.RETIRED, now)
        transition(projectionName, targetGeneration, ProjectionGenerationState.ACTIVE, now)
        updateAlias(projectionName, targetGeneration, now)
        return true
    }

    private fun insertGeneration(
        projectionName: String,
        generation: Int,
        state: ProjectionGenerationState,
        highWatermark: Long,
        now: Instant,
    ) {
        ProjectionGenerations.insert {
            it[id] = Uuid.V7.nextId()
            it[ProjectionGenerations.projectionName] = projectionName
            it[ProjectionGenerations.generation] = generation
            it[ProjectionGenerations.state] = state.name
            it[checkpoint] = 0
            it[ProjectionGenerations.highWatermark] = highWatermark
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    private fun transition(
        projectionName: String,
        generation: Int,
        state: ProjectionGenerationState,
        now: Instant,
    ) {
        ProjectionGenerations.update({ projectionGenerationIdentity(projectionName, generation) }) {
            it[ProjectionGenerations.state] = state.name
            it[updatedAt] = now
        }
    }

    private fun updateAlias(projectionName: String, generation: Int, now: Instant) {
        ProjectionAliases.update({ ProjectionAliases.projectionName eq projectionName }) {
            it[activeGeneration] = generation
            it[updatedAt] = now
        }
    }
}

@Repository
class ProjectionGenerationQueryRepository :
    EventSourcingExposedJdbcRepository<ProjectionGenerationEntity, UUID>(ProjectionGenerationEntity::class.java) {

    fun building(projectionName: String): ProjectionGeneration? = ProjectionGenerations.selectAll()
        .where {
            (ProjectionGenerations.projectionName eq projectionName) and
                (ProjectionGenerations.state eq ProjectionGenerationState.BUILDING.name)
        }
        .orderBy(ProjectionGenerations.generation to org.jetbrains.exposed.v1.core.SortOrder.DESC)
        .limit(1)
        .singleOrNull()
        ?.toProjectionGeneration()
}

@Repository
class ProjectionCheckpointRepository :
    EventSourcingExposedJdbcRepository<ProjectionGenerationEntity, UUID>(ProjectionGenerationEntity::class.java) {

    fun acquireLease(
        projectionName: String,
        generation: Int,
        ownerToken: UUID,
        now: Instant,
        duration: Duration,
    ): ProjectionLease? {
        require(!duration.isZero && !duration.isNegative) { "projection_lease_duration_invalid" }
        val current = lockedProjectionGenerationRow(projectionName, generation)?.toProjectionGeneration()
        val available = current != null && current.state != ProjectionGenerationState.FAILED &&
            (current.ownerToken == ownerToken || current.leaseUntil == null || current.leaseUntil <= now)
        if (!available) return null
        val leaseUntil = now.plus(duration)
        ProjectionGenerations.update({ projectionGenerationIdentity(projectionName, generation) }) {
            it[ProjectionGenerations.ownerToken] = ownerToken
            it[ProjectionGenerations.leaseUntil] = leaseUntil
            it[updatedAt] = now
        }
        return ProjectionLease(projectionName, generation, ownerToken, leaseUntil)
    }

    fun renewLease(lease: ProjectionLease, now: Instant, duration: Duration): ProjectionLease {
        require(!duration.isZero && !duration.isNegative) { "projection_lease_duration_invalid" }
        val renewedUntil = now.plus(duration)
        fencedUpdate(lease, now) { it[ProjectionGenerations.leaseUntil] = renewedUntil }
        return lease.copy(leaseUntil = renewedUntil)
    }

    fun releaseLease(lease: ProjectionLease, now: Instant) {
        fencedUpdate(lease, now) {
            it[ProjectionGenerations.ownerToken] = null
            it[ProjectionGenerations.leaseUntil] = null
        }
    }

    fun requireOwnership(lease: ProjectionLease): ProjectionGeneration {
        val generation = lockedProjectionGenerationRow(lease.projectionName, lease.generation)
            ?.toProjectionGeneration()
        if (generation?.ownerToken != lease.ownerToken || generation.state == ProjectionGenerationState.FAILED) {
            throw StaleProjectionOwnerException(lease.projectionName, lease.generation)
        }
        return generation
    }

    fun insertAppliedEvent(lease: ProjectionLease, eventId: UUID, globalPosition: Long, now: Instant): Boolean {
        require(globalPosition > 0) { "projection_global_position_invalid" }
        return ProjectionAppliedEvents.insertIgnore {
            it[id] = Uuid.V7.nextId()
            it[projectionName] = lease.projectionName
            it[generation] = lease.generation
            it[ProjectionAppliedEvents.eventId] = eventId
            it[ProjectionAppliedEvents.globalPosition] = globalPosition
            it[appliedAt] = now
        }.insertedCount == 1
    }

    fun advanceCheckpoint(lease: ProjectionLease, globalPosition: Long, now: Instant) {
        val current = requireOwnership(lease)
        if (globalPosition > current.checkpoint) {
            fencedUpdate(lease, now) { it[checkpoint] = globalPosition }
        }
    }

    fun raiseHighWatermark(lease: ProjectionLease, observedHighWatermark: Long, now: Instant) {
        val current = requireOwnership(lease)
        require(observedHighWatermark >= current.highWatermark) { "projection_high_watermark_regression" }
        fencedUpdate(lease, now) { it[highWatermark] = observedHighWatermark }
    }

    fun markFailed(lease: ProjectionLease, failedPosition: Long, failureDigest: String, now: Instant) {
        requireOwnership(lease)
        require(failedPosition > 0 && failureDigest.isNotBlank()) { "projection_failure_invalid" }
        fencedUpdate(lease, now) {
            it[state] = ProjectionGenerationState.FAILED.name
            it[ProjectionGenerations.failedPosition] = failedPosition
            it[ProjectionGenerations.failureDigest] = failureDigest
        }
    }

    private fun fencedUpdate(
        lease: ProjectionLease,
        now: Instant,
        body: ProjectionGenerations.(org.jetbrains.exposed.v1.core.statements.UpdateStatement) -> Unit,
    ) {
        val updated = ProjectionGenerations.update({
            projectionGenerationIdentity(lease.projectionName, lease.generation) and
                (ProjectionGenerations.ownerToken eq lease.ownerToken)
        }) {
            ProjectionGenerations.body(it)
            it[updatedAt] = now
        }
        if (updated != 1) throw StaleProjectionOwnerException(lease.projectionName, lease.generation)
    }
}

private fun projectionGenerationRow(projectionName: String, generation: Int): ResultRow? =
    ProjectionGenerations.selectAll()
        .where { projectionGenerationIdentity(projectionName, generation) }
        .singleOrNull()

private fun lockedProjectionGenerationRow(projectionName: String, generation: Int): ResultRow? =
    ProjectionGenerations.selectAll()
        .where { projectionGenerationIdentity(projectionName, generation) }
        .forUpdate()
        .singleOrNull()

private fun projectionGenerationIdentity(projectionName: String, generation: Int) =
    (ProjectionGenerations.projectionName eq projectionName) and
        (ProjectionGenerations.generation eq generation)

private fun ResultRow.toProjectionGeneration(): ProjectionGeneration = ProjectionGeneration(
    projectionName = this[ProjectionGenerations.projectionName],
    generation = this[ProjectionGenerations.generation],
    state = ProjectionGenerationState.valueOf(this[ProjectionGenerations.state]),
    checkpoint = this[ProjectionGenerations.checkpoint],
    highWatermark = this[ProjectionGenerations.highWatermark],
    ownerToken = this[ProjectionGenerations.ownerToken],
    leaseUntil = this[ProjectionGenerations.leaseUntil],
    failedPosition = this[ProjectionGenerations.failedPosition],
    failureDigest = this[ProjectionGenerations.failureDigest],
)

private fun validateProjectionIdentity(projectionName: String, generation: Int) {
    require(projectionName.isNotBlank() && generation > 0) { "projection_identity_invalid" }
}
