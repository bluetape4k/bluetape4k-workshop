package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import java.util.UUID

class EventStreamHeadEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<EventStreamHeadEntity>(EventStreamHeads)

    var tenantId by EventStreamHeads.tenantId
    var streamType by EventStreamHeads.streamType
    var streamId by EventStreamHeads.streamId
    var streamVersion by EventStreamHeads.streamVersion
    var latestEventHash by EventStreamHeads.latestEventHash
    var createdAt by EventStreamHeads.createdAt
    var updatedAt by EventStreamHeads.updatedAt
}

class DomainEventEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<DomainEventEntity>(DomainEvents)

    var tenantId by DomainEvents.tenantId
    var streamType by DomainEvents.streamType
    var streamId by DomainEvents.streamId
    var streamVersion by DomainEvents.streamVersion
    var globalPosition by DomainEvents.globalPosition
    var eventType by DomainEvents.eventType
    var schemaVersion by DomainEvents.schemaVersion
    var payload by DomainEvents.payload
    var metadata by DomainEvents.metadata
    var previousHash by DomainEvents.previousHash
    var eventHash by DomainEvents.eventHash
    var occurredAt by DomainEvents.occurredAt
    var recordedAt by DomainEvents.recordedAt
}

class CommandReceiptEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<CommandReceiptEntity>(CommandReceipts)

    var tenantId by CommandReceipts.tenantId
    var operation by CommandReceipts.operation
    var keyDigest by CommandReceipts.keyDigest
    var fingerprint by CommandReceipts.fingerprint
    var status by CommandReceipts.status
    var ownerToken by CommandReceipts.ownerToken
    var leaseUntil by CommandReceipts.leaseUntil
    var retentionUntil by CommandReceipts.retentionUntil
    var httpStatus by CommandReceipts.httpStatus
    var response by CommandReceipts.response
    var terminalAt by CommandReceipts.terminalAt
    var createdAt by CommandReceipts.createdAt
    var updatedAt by CommandReceipts.updatedAt
}

class AggregateSnapshotEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<AggregateSnapshotEntity>(AggregateSnapshots)

    var tenantId by AggregateSnapshots.tenantId
    var streamType by AggregateSnapshots.streamType
    var streamId by AggregateSnapshots.streamId
    var streamVersion by AggregateSnapshots.streamVersion
    var reducerVersion by AggregateSnapshots.reducerVersion
    var statePayload by AggregateSnapshots.statePayload
    var lastEventHash by AggregateSnapshots.lastEventHash
    var createdAt by AggregateSnapshots.createdAt
}

class ProjectionGenerationEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<ProjectionGenerationEntity>(ProjectionGenerations)

    var projectionName by ProjectionGenerations.projectionName
    var generation by ProjectionGenerations.generation
    var state by ProjectionGenerations.state
    var checkpoint by ProjectionGenerations.checkpoint
    var highWatermark by ProjectionGenerations.highWatermark
    var ownerToken by ProjectionGenerations.ownerToken
    var leaseUntil by ProjectionGenerations.leaseUntil
    var failedPosition by ProjectionGenerations.failedPosition
    var failureDigest by ProjectionGenerations.failureDigest
    var createdAt by ProjectionGenerations.createdAt
    var updatedAt by ProjectionGenerations.updatedAt
}

class BillingReadModelEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<BillingReadModelEntity>(BillingReadModels)

    var projectionName by BillingReadModels.projectionName
    var generation by BillingReadModels.generation
    var tenantId by BillingReadModels.tenantId
    var modelType by BillingReadModels.modelType
    var entryId by BillingReadModels.entryId
    var eventType by BillingReadModels.eventType
    var globalPosition by BillingReadModels.globalPosition
    var quantity by BillingReadModels.quantity
    var amount by BillingReadModels.amount
    var currency by BillingReadModels.currency
    var provenance by BillingReadModels.provenance
    var occurredAt by BillingReadModels.occurredAt
}

class ProjectionFailureEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<ProjectionFailureEntity>(ProjectionFailures)

    var projectionName by ProjectionFailures.projectionName
    var generation by ProjectionFailures.generation
    var eventId by ProjectionFailures.eventId
    var eventType by ProjectionFailures.eventType
    var globalPosition by ProjectionFailures.globalPosition
    var errorDigest by ProjectionFailures.errorDigest
    var attemptCount by ProjectionFailures.attemptCount
    var firstFailedAt by ProjectionFailures.firstFailedAt
    var lastFailedAt by ProjectionFailures.lastFailedAt
}
