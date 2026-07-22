@file:Suppress("MagicNumber") // SQL column sizes are schema declarations.

package io.bluetape4k.workshop.commerce.usagebilling.invoice.persistence

import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceJournal
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceLine
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

object InvoiceOutboxEvents : UUIDTable("invoice_outbox_event", "outbox_event_id") {
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

object InvoiceLines : UUIDTable("invoice_line", "invoice_line_id") {
    val sourceEventId = javaUUID("source_event_id")
    val correctionOf = javaUUID("correction_of").nullable()
    val amount = decimal("amount", 19, 6)

    init {
        uniqueIndex(sourceEventId)
    }
}

class InvoiceOutboxEventEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<InvoiceOutboxEventEntity>(InvoiceOutboxEvents)

    var eventId by InvoiceOutboxEvents.eventId
    var tenantId by InvoiceOutboxEvents.tenantId
    var eventType by InvoiceOutboxEvents.eventType
    var aggregateType by InvoiceOutboxEvents.aggregateType
    var aggregateId by InvoiceOutboxEvents.aggregateId
    var aggregateVersion by InvoiceOutboxEvents.aggregateVersion
    var partitionKey by InvoiceOutboxEvents.partitionKey
    var payload by InvoiceOutboxEvents.payload
    var payloadDigest by InvoiceOutboxEvents.payloadDigest
    var status by InvoiceOutboxEvents.status
    var attempt by InvoiceOutboxEvents.attempt
    var nextAttemptAt by InvoiceOutboxEvents.nextAttemptAt
    var claimOwner by InvoiceOutboxEvents.claimOwner
    var claimUntil by InvoiceOutboxEvents.claimUntil
    var createdAt by InvoiceOutboxEvents.createdAt
    var updatedAt by InvoiceOutboxEvents.updatedAt
}

class InvoiceLineEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<InvoiceLineEntity>(InvoiceLines)

    var sourceEventId by InvoiceLines.sourceEventId
    var correctionOf by InvoiceLines.correctionOf
    var amount by InvoiceLines.amount
}

abstract class InvoiceExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(ExposedEntityInformationImpl(domainClass))

abstract class AppendOnlyInvoiceExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : InvoiceExposedJdbcRepository<E, ID>(domainClass) {
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
class InvoiceOutboxRepository :
    AppendOnlyInvoiceExposedJdbcRepository<InvoiceOutboxEventEntity, UUID>(InvoiceOutboxEventEntity::class.java)

@Repository
class InvoiceLineRepository :
    AppendOnlyInvoiceExposedJdbcRepository<InvoiceLineEntity, UUID>(InvoiceLineEntity::class.java)

@Repository
class ExposedInvoiceJournal : InvoiceJournal {
    override val lines: List<InvoiceLine>
        get() = InvoiceLineEntity.all().map { InvoiceLine(it.sourceEventId, it.correctionOf, it.amount) }

    override fun findLine(sourceEventId: UUID): InvoiceLine? =
        InvoiceLineEntity.find { InvoiceLines.sourceEventId eq sourceEventId }
            .firstOrNull()
            ?.let { InvoiceLine(it.sourceEventId, it.correctionOf, it.amount) }

    override fun append(line: InvoiceLine) {
        InvoiceLineEntity.new {
            sourceEventId = line.sourceEventId
            correctionOf = line.correctionOf
            amount = line.amount
        }
    }
}
