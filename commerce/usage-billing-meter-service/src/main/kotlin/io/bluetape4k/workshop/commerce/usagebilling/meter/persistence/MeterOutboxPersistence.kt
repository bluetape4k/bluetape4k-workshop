@file:Suppress("MagicNumber") // SQL column sizes are schema declarations.

package io.bluetape4k.workshop.commerce.usagebilling.meter.persistence

import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformationImpl
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp
import java.util.UUID

object MeterOutboxEvents : UUIDTable("meter_outbox_event", "outbox_event_id") {
    val eventId = javaUUID("event_id")
    val tenantId = varchar("tenant_id", 64)
    val eventType = varchar("event_type", 128)
    val aggregateType = varchar("aggregate_type", 64)
    val aggregateId = varchar("aggregate_id", 128)
    val aggregateVersion = long("aggregate_version")
    val partitionKey = varchar("partition_key", 256)
    val payload = text("payload")
    val payloadDigest = varchar("payload_digest", 64)
    val status = varchar("status", 32)
    val attempt = integer("attempt").default(0)
    val nextAttemptAt = timestamp("next_attempt_at").nullable()
    val claimOwner = varchar("claim_owner", 128).nullable()
    val claimUntil = timestamp("claim_until").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(eventId)
        index(false, status, nextAttemptAt, id)
    }
}

class MeterOutboxEventEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<MeterOutboxEventEntity>(MeterOutboxEvents)

    var eventId by MeterOutboxEvents.eventId
    var tenantId by MeterOutboxEvents.tenantId
    var eventType by MeterOutboxEvents.eventType
    var aggregateType by MeterOutboxEvents.aggregateType
    var aggregateId by MeterOutboxEvents.aggregateId
    var aggregateVersion by MeterOutboxEvents.aggregateVersion
    var partitionKey by MeterOutboxEvents.partitionKey
    var payload by MeterOutboxEvents.payload
    var payloadDigest by MeterOutboxEvents.payloadDigest
    var status by MeterOutboxEvents.status
    var attempt by MeterOutboxEvents.attempt
    var nextAttemptAt by MeterOutboxEvents.nextAttemptAt
    var claimOwner by MeterOutboxEvents.claimOwner
    var claimUntil by MeterOutboxEvents.claimUntil
    var createdAt by MeterOutboxEvents.createdAt
    var updatedAt by MeterOutboxEvents.updatedAt
}

abstract class MeterExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(ExposedEntityInformationImpl(domainClass))

abstract class AppendOnlyMeterExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : MeterExposedJdbcRepository<E, ID>(domainClass) {
    final override fun <S : E> save(entity: S): S = immutableMutation()
    final override fun <S : E> saveAll(entities: Iterable<S>): List<S> = immutableMutation()
    final override fun deleteById(id: ID): Unit = immutableMutation()
    final override fun delete(entity: E): Unit = immutableMutation()
    final override fun deleteAllById(ids: Iterable<ID>): Unit = immutableMutation()
    final override fun deleteAll(entities: Iterable<E>): Unit = immutableMutation()
    final override fun deleteAll(): Unit = immutableMutation()

    protected fun <T> immutableMutation(): T = throw UnsupportedOperationException("append-only repository")
}

class MeterOutboxRepository :
    AppendOnlyMeterExposedJdbcRepository<MeterOutboxEventEntity, UUID>(MeterOutboxEventEntity::class.java)
