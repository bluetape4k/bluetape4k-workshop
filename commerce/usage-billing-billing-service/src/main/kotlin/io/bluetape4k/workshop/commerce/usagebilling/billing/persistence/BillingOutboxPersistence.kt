@file:Suppress("MagicNumber") // SQL column sizes are schema declarations.

package io.bluetape4k.workshop.commerce.usagebilling.billing.persistence

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

object BillingOutboxEvents : UUIDTable("billing_outbox_event", "outbox_event_id") {
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

class BillingOutboxEventEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<BillingOutboxEventEntity>(BillingOutboxEvents)

    var eventId by BillingOutboxEvents.eventId
    var tenantId by BillingOutboxEvents.tenantId
    var eventType by BillingOutboxEvents.eventType
    var aggregateType by BillingOutboxEvents.aggregateType
    var aggregateId by BillingOutboxEvents.aggregateId
    var aggregateVersion by BillingOutboxEvents.aggregateVersion
    var partitionKey by BillingOutboxEvents.partitionKey
    var payload by BillingOutboxEvents.payload
    var payloadDigest by BillingOutboxEvents.payloadDigest
    var status by BillingOutboxEvents.status
    var attempt by BillingOutboxEvents.attempt
    var nextAttemptAt by BillingOutboxEvents.nextAttemptAt
    var claimOwner by BillingOutboxEvents.claimOwner
    var claimUntil by BillingOutboxEvents.claimUntil
    var createdAt by BillingOutboxEvents.createdAt
    var updatedAt by BillingOutboxEvents.updatedAt
}

abstract class BillingExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(ExposedEntityInformationImpl(domainClass))

abstract class AppendOnlyBillingExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : BillingExposedJdbcRepository<E, ID>(domainClass) {
    final override fun <S : E> save(entity: S): S = immutableMutation()
    final override fun <S : E> saveAll(entities: Iterable<S>): List<S> = immutableMutation()
    final override fun deleteById(id: ID): Unit = immutableMutation()
    final override fun delete(entity: E): Unit = immutableMutation()
    final override fun deleteAllById(ids: Iterable<ID>): Unit = immutableMutation()
    final override fun deleteAll(entities: Iterable<E>): Unit = immutableMutation()
    final override fun deleteAll(): Unit = immutableMutation()

    protected fun <T> immutableMutation(): T = throw UnsupportedOperationException("append-only repository")
}

class BillingOutboxRepository :
    AppendOnlyBillingExposedJdbcRepository<BillingOutboxEventEntity, UUID>(BillingOutboxEventEntity::class.java)
