@file:Suppress("MagicNumber") // SQL column sizes are schema declarations.

package io.bluetape4k.workshop.commerce.usagebilling.invoice.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceInboxOutcome
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceJournal
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceLine
import io.bluetape4k.workshop.commerce.usagebilling.invoice.integration.InvoiceIntegrationEnvelope
import io.bluetape4k.workshop.commerce.usagebilling.invoice.messaging.InvoiceOutboxJournal
import io.bluetape4k.workshop.commerce.usagebilling.invoice.messaging.InvoiceOutboxLease
import io.bluetape4k.workshop.commerce.usagebilling.invoice.messaging.InvoiceOutboxStatus
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformationImpl
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
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

object InvoiceInboxEvents : UUIDTable("invoice_inbox_event", "inbox_event_id") {
    val eventId = javaUUID("event_id")
    val tenantId = varchar("tenant_id", 64)
    val eventType = varchar("event_type", 128)
    val payloadDigest = varchar("payload_digest", 64)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(tenantId, eventId)
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

class InvoiceInboxEventEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<InvoiceInboxEventEntity>(InvoiceInboxEvents)

    var eventId by InvoiceInboxEvents.eventId
    var tenantId by InvoiceInboxEvents.tenantId
    var eventType by InvoiceInboxEvents.eventType
    var payloadDigest by InvoiceInboxEvents.payloadDigest
    var createdAt by InvoiceInboxEvents.createdAt
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
class InvoiceInboxRepository :
    AppendOnlyInvoiceExposedJdbcRepository<InvoiceInboxEventEntity, UUID>(InvoiceInboxEventEntity::class.java)

@Repository
class ExposedInvoiceJournal : InvoiceJournal {
    override val lines: List<InvoiceLine>
        get() = InvoiceLineEntity.all().map { InvoiceLine(it.sourceEventId, it.correctionOf, it.amount) }

    override fun findLine(sourceEventId: UUID): InvoiceLine? =
        InvoiceLineEntity.find { InvoiceLines.sourceEventId eq sourceEventId }
            .firstOrNull()
            ?.let { InvoiceLine(it.sourceEventId, it.correctionOf, it.amount) }

    override fun apply(event: InvoiceInboxEvent): InvoiceInboxOutcome {
        val now = Instant.now()
        val inserted = InvoiceInboxEvents.insertIgnore {
            it[eventId] = event.eventId
            it[tenantId] = event.tenantId
            it[eventType] = event.eventType
            it[payloadDigest] = event.payloadDigest
            it[createdAt] = now
        }.insertedCount == 1
        if (!inserted) {
            val existing = InvoiceInboxEvents.selectAll().where {
                (InvoiceInboxEvents.tenantId eq event.tenantId) and
                    (InvoiceInboxEvents.eventId eq event.eventId)
            }.singleOrNull()
            return when (existing?.get(InvoiceInboxEvents.payloadDigest)) {
                event.payloadDigest -> InvoiceInboxOutcome.DUPLICATE
                else -> InvoiceInboxOutcome.QUARANTINED
            }
        }
        InvoiceLineEntity.new {
            sourceEventId = event.eventId
            correctionOf = event.correctionOf
            amount = event.amount
        }
        appendInvoiceEvent(event, now)
        return InvoiceInboxOutcome.APPLIED
    }

    private fun appendInvoiceEvent(event: InvoiceInboxEvent, now: Instant) {
        val outboxEventId = Uuid.V7.nextId()
        val aggregateId = (event.correctionOf ?: event.eventId).toString()
        val aggregateVersion = InvoiceOutboxEventEntity.find {
            InvoiceOutboxEvents.aggregateId eq aggregateId
        }.count() + 1L
        val invoiceEventType = if (event.correctionOf == null) "InvoiceIssued" else "InvoiceCorrectionIssued"
        val payload = Jackson.defaultJsonMapper.writeValueAsString(
            linkedMapOf(
                "sourceEventId" to event.eventId.toString(),
                "correctionOf" to event.correctionOf?.toString(),
                "amount" to event.amount.toPlainString(),
            ),
        )
        val envelope = InvoiceIntegrationEnvelope.create(
            eventId = outboxEventId,
            eventType = invoiceEventType,
            schemaVersion = 1,
            tenantId = event.tenantId,
            aggregateId = aggregateId,
            aggregateVersion = aggregateVersion,
            payload = payload,
            occurredAt = now,
            recordedAt = now,
        )
        InvoiceOutboxEventEntity.new {
            eventId = outboxEventId
            tenantId = event.tenantId
            eventType = invoiceEventType
            aggregateType = envelope.aggregateType
            this.aggregateId = aggregateId
            this.aggregateVersion = aggregateVersion
            partitionKey = envelope.partitionKey()
            this.payload = envelope.wirePayload()
            payloadDigest = envelope.wirePayloadDigest()
            status = InvoiceOutboxStatus.PENDING.name
            attempt = 0
            nextAttemptAt = null
            claimOwner = null
            claimUntil = null
            createdAt = now
            updatedAt = now
        }
    }
}

@Repository
class ExposedInvoiceOutboxJournal : InvoiceOutboxJournal {
    @Transactional
    override fun claim(owner: String, now: Instant, limit: Int): List<InvoiceOutboxLease> =
        InvoiceOutboxEvents.selectAll()
            .where { claimableAt(now) }
            .orderBy(InvoiceOutboxEvents.createdAt to SortOrder.ASC, InvoiceOutboxEvents.id to SortOrder.ASC)
            .limit(limit)
            .mapNotNull { row ->
                val eventId = row[InvoiceOutboxEvents.eventId]
                val claimed = InvoiceOutboxEvents.update({
                    (InvoiceOutboxEvents.eventId eq eventId) and claimableAt(now)
                }) {
                    it[status] = InvoiceOutboxStatus.CLAIMED.name
                    it[claimOwner] = owner
                    it[claimUntil] = now.plus(CLAIM_LEASE)
                    it[updatedAt] = now
                }
                if (claimed != 1) return@mapNotNull null
                InvoiceOutboxEvents.selectAll()
                    .where { InvoiceOutboxEvents.eventId eq eventId }
                    .singleOrNull()
                    ?.let { claimedRow ->
                        InvoiceOutboxLease(
                            claimedRow[InvoiceOutboxEvents.eventId],
                            claimedRow[InvoiceOutboxEvents.partitionKey],
                            claimedRow[InvoiceOutboxEvents.payload],
                        )
                    }
            }

    @Transactional
    override fun markPublished(eventId: UUID, owner: String, now: Instant): Boolean =
        InvoiceOutboxEvents.update({ claimedBy(owner, eventId, now) }) {
            it[status] = InvoiceOutboxStatus.PUBLISHED.name
            it[claimOwner] = null
            it[claimUntil] = null
            it[nextAttemptAt] = null
            it[updatedAt] = now
        } == 1

    @Transactional
    override fun markRetryWait(eventId: UUID, owner: String, now: Instant): Boolean =
        InvoiceOutboxEvents.selectAll().where { claimedBy(owner, eventId, now) }.singleOrNull()?.let { row ->
            val attempts = row[InvoiceOutboxEvents.attempt] + 1
            InvoiceOutboxEvents.update({ claimedBy(owner, eventId, now) }) {
                it[attempt] = attempts
                it[status] = if (attempts >= MAX_ATTEMPTS) {
                    InvoiceOutboxStatus.QUARANTINED.name
                } else {
                    InvoiceOutboxStatus.RETRY_WAIT.name
                }
                it[claimOwner] = null
                it[claimUntil] = null
                it[nextAttemptAt] = now.plus(RETRY_DELAY)
                it[updatedAt] = now
            } == 1
        } ?: false

    private fun claimableAt(now: Instant): Op<Boolean> =
        (InvoiceOutboxEvents.status eq InvoiceOutboxStatus.PENDING.name) or
            ((InvoiceOutboxEvents.status eq InvoiceOutboxStatus.RETRY_WAIT.name) and
                (InvoiceOutboxEvents.nextAttemptAt lessEq now)) or
            ((InvoiceOutboxEvents.status eq InvoiceOutboxStatus.CLAIMED.name) and
                (InvoiceOutboxEvents.claimUntil lessEq now))

    private fun claimedBy(owner: String, eventId: UUID, now: Instant): Op<Boolean> =
        (InvoiceOutboxEvents.eventId eq eventId) and
            (InvoiceOutboxEvents.status eq InvoiceOutboxStatus.CLAIMED.name) and
            (InvoiceOutboxEvents.claimOwner eq owner) and
            (InvoiceOutboxEvents.claimUntil greater now)

    private companion object {
        val CLAIM_LEASE: Duration = Duration.ofSeconds(30)
        val RETRY_DELAY: Duration = Duration.ofSeconds(1)
        const val MAX_ATTEMPTS = 3
    }
}
