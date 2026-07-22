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
