@file:Suppress("MagicNumber") // SQL column sizes are schema declarations.

package io.bluetape4k.workshop.commerce.usagebilling.billing.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxJournal
import io.bluetape4k.workshop.commerce.usagebilling.billing.integration.BillingIntegrationEnvelope
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformationImpl
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp
import org.springframework.stereotype.Repository
import java.time.Instant
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

object BillingPricingEvidence : UUIDTable("billing_pricing_evidence", "pricing_evidence_id") {
    val meterCode = varchar("meter_code", 64)

    init {
        uniqueIndex(meterCode)
    }
}

object BillingInboxEvents : UUIDTable("billing_inbox_event", "inbox_event_id") {
    val eventId = javaUUID("event_id")
    val tenantId = varchar("tenant_id", 64)
    val aggregateId = varchar("aggregate_id", 128)
    val aggregateVersion = long("aggregate_version")
    val payloadDigest = varchar("payload_digest", 64)
    val status = varchar("status", 32)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(eventId)
        uniqueIndex(tenantId, aggregateId, aggregateVersion)
    }
}

object BillingCharges : UUIDTable("billing_charge", "charge_id") {
    val sourceEventId = javaUUID("source_event_id")
    val tenantId = varchar("tenant_id", 64)
    val aggregateId = varchar("aggregate_id", 128)
    val aggregateVersion = long("aggregate_version")
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(sourceEventId)
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

class BillingPricingEvidenceEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<BillingPricingEvidenceEntity>(BillingPricingEvidence)

    var meterCode by BillingPricingEvidence.meterCode
}

class BillingInboxEventEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<BillingInboxEventEntity>(BillingInboxEvents)

    var eventId by BillingInboxEvents.eventId
    var tenantId by BillingInboxEvents.tenantId
    var aggregateId by BillingInboxEvents.aggregateId
    var aggregateVersion by BillingInboxEvents.aggregateVersion
    var payloadDigest by BillingInboxEvents.payloadDigest
    var status by BillingInboxEvents.status
    var createdAt by BillingInboxEvents.createdAt
}

class BillingChargeEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<BillingChargeEntity>(BillingCharges)

    var sourceEventId by BillingCharges.sourceEventId
    var tenantId by BillingCharges.tenantId
    var aggregateId by BillingCharges.aggregateId
    var aggregateVersion by BillingCharges.aggregateVersion
    var createdAt by BillingCharges.createdAt
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

@Repository
class BillingOutboxRepository :
    AppendOnlyBillingExposedJdbcRepository<BillingOutboxEventEntity, UUID>(BillingOutboxEventEntity::class.java)

@Repository
class BillingChargeRepository :
    AppendOnlyBillingExposedJdbcRepository<BillingChargeEntity, UUID>(BillingChargeEntity::class.java)

@Repository
class BillingPricingEvidenceRepository :
    AppendOnlyBillingExposedJdbcRepository<BillingPricingEvidenceEntity, UUID>(
        BillingPricingEvidenceEntity::class.java,
    ) {
    fun append(meterCode: String) {
        BillingPricingEvidenceEntity.new {
            this.meterCode = meterCode
        }
    }
}

@Repository
class BillingInboxRepository :
    AppendOnlyBillingExposedJdbcRepository<BillingInboxEventEntity, UUID>(BillingInboxEventEntity::class.java)

@Repository
class ExposedBillingInboxJournal : BillingInboxJournal {
    override val pricingMeters: Set<String>
        get() = BillingPricingEvidenceEntity.all().map { it.meterCode }.toSet()

    override val appliedEventIds: Set<UUID>
        get() = BillingInboxEventEntity.find { BillingInboxEvents.status eq "APPLIED" }.map { it.eventId }.toSet()

    override fun digestFor(eventId: UUID): String? =
        BillingInboxEventEntity.find { BillingInboxEvents.eventId eq eventId }.firstOrNull()?.payloadDigest

    override fun expectedVersion(tenantId: String, aggregateId: String): Long =
        BillingInboxEventEntity.find {
            (BillingInboxEvents.tenantId eq tenantId) and
                (BillingInboxEvents.aggregateId eq aggregateId) and
                (BillingInboxEvents.status eq "APPLIED")
        }.count() + 1L

    override fun apply(event: BillingInboxEvent) {
        val now = Instant.now()
        BillingInboxEventEntity.new {
            eventId = event.eventId
            tenantId = event.tenantId
            aggregateId = event.aggregateId
            aggregateVersion = event.aggregateVersion
            payloadDigest = event.payloadDigest
            status = "APPLIED"
            createdAt = now
        }
        BillingChargeEntity.new {
            sourceEventId = event.eventId
            tenantId = event.tenantId
            aggregateId = event.aggregateId
            aggregateVersion = event.aggregateVersion
            createdAt = now
        }
        val outboxEventId = Uuid.V7.nextId()
        val envelope = BillingIntegrationEnvelope.create(
            eventId = outboxEventId,
            eventType = "ChargeRated",
            schemaVersion = 1,
            tenantId = event.tenantId,
            aggregateId = event.aggregateId,
            aggregateVersion = event.aggregateVersion,
            payload = """{"sourceEventId":"${event.eventId}"}""",
            occurredAt = now,
            recordedAt = now,
        )
        BillingOutboxEventEntity.new {
            eventId = outboxEventId
            tenantId = event.tenantId
            eventType = envelope.eventType
            aggregateType = envelope.aggregateType
            aggregateId = event.aggregateId
            aggregateVersion = event.aggregateVersion
            partitionKey = envelope.partitionKey()
            payload = envelope.wirePayload()
            payloadDigest = envelope.wirePayloadDigest()
            status = "PENDING"
            attempt = 0
            nextAttemptAt = null
            claimOwner = null
            claimUntil = null
            createdAt = now
            updatedAt = now
        }
    }
}
