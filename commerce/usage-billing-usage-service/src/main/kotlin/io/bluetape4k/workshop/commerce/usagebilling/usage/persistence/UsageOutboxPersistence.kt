@file:Suppress("MagicNumber") // SQL column sizes are schema declarations.

package io.bluetape4k.workshop.commerce.usagebilling.usage.persistence

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

object UsageOutboxEvents : UUIDTable("usage_outbox_event", "outbox_event_id") {
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

class UsageOutboxEventEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<UsageOutboxEventEntity>(UsageOutboxEvents)

    var eventId by UsageOutboxEvents.eventId
    var tenantId by UsageOutboxEvents.tenantId
    var eventType by UsageOutboxEvents.eventType
    var aggregateType by UsageOutboxEvents.aggregateType
    var aggregateId by UsageOutboxEvents.aggregateId
    var aggregateVersion by UsageOutboxEvents.aggregateVersion
    var partitionKey by UsageOutboxEvents.partitionKey
    var payload by UsageOutboxEvents.payload
    var payloadDigest by UsageOutboxEvents.payloadDigest
    var status by UsageOutboxEvents.status
    var attempt by UsageOutboxEvents.attempt
    var nextAttemptAt by UsageOutboxEvents.nextAttemptAt
    var claimOwner by UsageOutboxEvents.claimOwner
    var claimUntil by UsageOutboxEvents.claimUntil
    var createdAt by UsageOutboxEvents.createdAt
    var updatedAt by UsageOutboxEvents.updatedAt
}

abstract class UsageExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(ExposedEntityInformationImpl(domainClass))

abstract class AppendOnlyUsageExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : UsageExposedJdbcRepository<E, ID>(domainClass) {
    final override fun <S : E> save(entity: S): S = immutableMutation()
    final override fun <S : E> saveAll(entities: Iterable<S>): List<S> = immutableMutation()
    final override fun deleteById(id: ID): Unit = immutableMutation()
    final override fun delete(entity: E): Unit = immutableMutation()
    final override fun deleteAllById(ids: Iterable<ID>): Unit = immutableMutation()
    final override fun deleteAll(entities: Iterable<E>): Unit = immutableMutation()
    final override fun deleteAll(): Unit = immutableMutation()

    protected fun <T> immutableMutation(): T = throw UnsupportedOperationException("append-only repository")
}

class UsageOutboxRepository :
    AppendOnlyUsageExposedJdbcRepository<UsageOutboxEventEntity, UUID>(UsageOutboxEventEntity::class.java)
