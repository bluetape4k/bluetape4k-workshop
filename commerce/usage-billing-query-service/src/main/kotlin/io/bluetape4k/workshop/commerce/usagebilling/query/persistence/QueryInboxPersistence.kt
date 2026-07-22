@file:Suppress("MagicNumber") // SQL column sizes are schema declarations.

package io.bluetape4k.workshop.commerce.usagebilling.query.persistence

import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryProjectionJournal
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformationImpl
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp
import org.springframework.stereotype.Repository
import java.util.UUID

object QueryInboxEvents : UUIDTable("query_inbox_event", "inbox_event_id") {
    val eventId = javaUUID("event_id")
    val tenantId = varchar("tenant_id", 64)
    val eventType = varchar("event_type", 128)
    val aggregateType = varchar("aggregate_type", 64)
    val aggregateId = varchar("aggregate_id", 128)
    val aggregateVersion = long("aggregate_version")
    val partitionKey = varchar("partition_key", 256)
    val payload = text("payload")
    val payloadDigest = varchar("payload_digest", 64)
    val outcome = varchar("outcome", 32)
    val attempt = integer("attempt").default(0)
    val nextAttemptAt = timestamp("next_attempt_at").nullable()
    val claimOwner = varchar("claim_owner", 128).nullable()
    val claimUntil = timestamp("claim_until").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(eventId)
        index(false, outcome, nextAttemptAt, id)
    }
}

object QueryReadModels : UUIDTable("query_read_model", "read_model_id") {
    val sourceEventId = javaUUID("source_event_id")
    val tenantId = varchar("tenant_id", 64)
    val eventType = varchar("event_type", 128)

    init {
        uniqueIndex(sourceEventId)
    }
}

object QueryCheckpoints : UUIDTable("query_checkpoint", "checkpoint_id") {
    val name = varchar("name", 64)
    val position = long("position")

    init {
        uniqueIndex(name)
    }
}

class QueryInboxEventEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<QueryInboxEventEntity>(QueryInboxEvents)

    var eventId by QueryInboxEvents.eventId
    var tenantId by QueryInboxEvents.tenantId
    var eventType by QueryInboxEvents.eventType
    var aggregateType by QueryInboxEvents.aggregateType
    var aggregateId by QueryInboxEvents.aggregateId
    var aggregateVersion by QueryInboxEvents.aggregateVersion
    var partitionKey by QueryInboxEvents.partitionKey
    var payload by QueryInboxEvents.payload
    var payloadDigest by QueryInboxEvents.payloadDigest
    var outcome by QueryInboxEvents.outcome
    var attempt by QueryInboxEvents.attempt
    var nextAttemptAt by QueryInboxEvents.nextAttemptAt
    var claimOwner by QueryInboxEvents.claimOwner
    var claimUntil by QueryInboxEvents.claimUntil
    var createdAt by QueryInboxEvents.createdAt
    var updatedAt by QueryInboxEvents.updatedAt
}

class QueryReadModelEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<QueryReadModelEntity>(QueryReadModels)

    var sourceEventId by QueryReadModels.sourceEventId
    var tenantId by QueryReadModels.tenantId
    var eventType by QueryReadModels.eventType
}

class QueryCheckpointEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<QueryCheckpointEntity>(QueryCheckpoints)

    var name by QueryCheckpoints.name
    var position by QueryCheckpoints.position
}

abstract class QueryExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(ExposedEntityInformationImpl(domainClass))

abstract class AppendOnlyQueryExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : QueryExposedJdbcRepository<E, ID>(domainClass) {
    final override fun <S : E> save(entity: S): S = immutableMutation()
    final override fun <S : E> saveAll(entities: Iterable<S>): List<S> = immutableMutation()
    final override fun deleteById(id: ID): Unit = immutableMutation()
    final override fun delete(entity: E): Unit = immutableMutation()
    final override fun deleteAllById(ids: Iterable<ID>): Unit = immutableMutation()
    final override fun deleteAll(entities: Iterable<E>): Unit = immutableMutation()
    final override fun deleteAll(): Unit = immutableMutation()

    protected fun <T> immutableMutation(): T = throw UnsupportedOperationException("append-only repository")
}

@Repository
class QueryInboxRepository :
    AppendOnlyQueryExposedJdbcRepository<QueryInboxEventEntity, UUID>(QueryInboxEventEntity::class.java)

@Repository
class QueryReadModelRepository :
    AppendOnlyQueryExposedJdbcRepository<QueryReadModelEntity, UUID>(QueryReadModelEntity::class.java)

@Repository
class QueryCheckpointRepository :
    AppendOnlyQueryExposedJdbcRepository<QueryCheckpointEntity, UUID>(QueryCheckpointEntity::class.java)

@Repository
class ExposedQueryProjectionJournal : QueryProjectionJournal {
    override val readModelEventIds: Set<UUID>
        get() = QueryReadModelEntity.all().map { it.sourceEventId }.toSet()

    override var checkpoint: Long
        get() = QueryCheckpointEntity.find { QueryCheckpoints.name eq "invoice" }.firstOrNull()?.position ?: 0
        set(value) {
            val checkpoint = QueryCheckpointEntity.find { QueryCheckpoints.name eq "invoice" }.firstOrNull()
            if (checkpoint == null) {
                QueryCheckpointEntity.new {
                    name = "invoice"
                    position = value
                }
            } else {
                checkpoint.position = value
            }
        }

    override fun hasEvent(eventId: UUID): Boolean =
        QueryReadModelEntity.find { QueryReadModels.sourceEventId eq eventId }.firstOrNull() != null

    override fun apply(event: QueryInboxEvent) {
        QueryInboxEventEntity.new {
            eventId = event.eventId
            tenantId = event.tenantId
            eventType = event.eventType
            aggregateType = event.aggregateType
            aggregateId = event.aggregateId
            aggregateVersion = event.aggregateVersion
            partitionKey = "${event.tenantId}|${event.aggregateType}|${event.aggregateId}"
            payload = event.payload
            payloadDigest = event.payloadDigest
            outcome = "APPLIED"
            createdAt = event.receivedAt
            updatedAt = event.receivedAt
        }
        QueryReadModelEntity.new {
            sourceEventId = event.eventId
            tenantId = event.tenantId
            eventType = event.eventType
        }
        checkpoint += 1
    }
}
