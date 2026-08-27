package io.bluetape4k.workshop.leader.jobsafety.persistence

import io.bluetape4k.spring.data.exposed.jdbc.annotation.ExposedEntity
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

@ExposedEntity
class JobAssignmentEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<JobAssignmentEntity>(JobAssignments)

    var tenantId by JobAssignments.tenantId
    var membershipRevision by JobAssignments.membershipRevision
    var regionId by JobAssignments.regionId
    var regionEpoch by JobAssignments.regionEpoch
    var active by JobAssignments.active
}

@ExposedEntity
class JobRolloutMarkerEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<JobRolloutMarkerEntity>(JobRolloutMarkers)

    var markerName by JobRolloutMarkers.markerName
    var namespaceEpoch by JobRolloutMarkers.namespaceEpoch
    var minimumWriterVersion by JobRolloutMarkers.minimumWriterVersion
    var checkpointSchemaVersion by JobRolloutMarkers.checkpointSchemaVersion
}

@ExposedEntity
class JobResourceEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<JobResourceEntity>(JobResources)

    var conflictKey by JobResources.conflictKey
    var namespaceEpoch by JobResources.namespaceEpoch
    var lastAcceptedFenceEpoch by JobResources.lastAcceptedFenceEpoch
    var lastAcceptedFence by JobResources.lastAcceptedFence
    var summaryValue by JobResources.summaryValue
    var updatedAt by JobResources.updatedAt
}

@ExposedEntity
class JobExecutionEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<JobExecutionEntity>(JobExecutions)

    var operationId by JobExecutions.operationId
    var jobName by JobExecutions.jobName
    var tenantId by JobExecutions.tenantId
    var conflictKey by JobExecutions.conflictKey
    var fencingOwnerId by JobExecutions.fencingOwnerId
    var fencingTokenEpoch by JobExecutions.fencingTokenEpoch
    var fencingToken by JobExecutions.fencingToken
    var state by JobExecutions.state
    var rejection by JobExecutions.rejection
    var contractVersion by JobExecutions.contractVersion
    var createdAt by JobExecutions.createdAt
    var updatedAt by JobExecutions.updatedAt
}

@ExposedEntity
class JobCheckpointEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<JobCheckpointEntity>(JobCheckpoints)

    var conflictKey by JobCheckpoints.conflictKey
    var fencingTokenEpoch by JobCheckpoints.fencingTokenEpoch
    var fencingToken by JobCheckpoints.fencingToken
    var schemaVersion by JobCheckpoints.schemaVersion
    var summaryValue by JobCheckpoints.summaryValue
    var updatedAt by JobCheckpoints.updatedAt
}

@ExposedEntity
class JobOutboxEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<JobOutboxEntity>(JobOutboxEntries)

    var operationId by JobOutboxEntries.operationId
    var effectType by JobOutboxEntries.effectType
    var status by JobOutboxEntries.status
    var attemptCount by JobOutboxEntries.attemptCount
    var nextAttemptAt by JobOutboxEntries.nextAttemptAt
    var createdAt by JobOutboxEntries.createdAt
    var updatedAt by JobOutboxEntries.updatedAt
}

@ExposedEntity
class JobEffectReceiptEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<JobEffectReceiptEntity>(JobEffectReceipts)

    var provider by JobEffectReceipts.provider
    var operationId by JobEffectReceipts.operationId
    var status by JobEffectReceipts.status
    var createdAt by JobEffectReceipts.createdAt
}
