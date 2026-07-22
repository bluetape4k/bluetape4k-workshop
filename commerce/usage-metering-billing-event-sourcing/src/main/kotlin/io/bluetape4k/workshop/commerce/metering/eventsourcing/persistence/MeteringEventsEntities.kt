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
