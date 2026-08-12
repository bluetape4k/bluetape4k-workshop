@file:Suppress("MagicNumber") // SQL column sizes are schema declarations.

package io.bluetape4k.workshop.commerce.usagebilling.billing.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentCommand
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentJournal
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentOutcome
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxJournal
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidenceEvent
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidenceOutcome
import io.bluetape4k.workshop.commerce.usagebilling.billing.integration.BillingIntegrationEnvelope
import io.bluetape4k.workshop.commerce.usagebilling.billing.messaging.BillingOutboxJournal
import io.bluetape4k.workshop.commerce.usagebilling.billing.messaging.BillingOutboxLease
import io.bluetape4k.workshop.commerce.usagebilling.billing.messaging.BillingOutboxStatus
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformationImpl
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
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
    val tenantId = varchar("tenant_id", 64)
    val meterCode = varchar("meter_code", 64)
    val currency = varchar("currency", 3)
    val unitPrice = decimal("unit_price", 19, 6)
    val effectiveAt = timestamp("effective_at")

    init {
        uniqueIndex(tenantId, meterCode, currency, effectiveAt)
    }
}

object BillingPriceEvidenceInboxEvents : UUIDTable(
    "billing_price_evidence_inbox_event",
    "price_evidence_inbox_event_id",
) {
    val eventId = javaUUID("event_id")
    val tenantId = varchar("tenant_id", 64)
    val payloadDigest = varchar("payload_digest", 64)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(tenantId, eventId)
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
    val currency = varchar("currency", 3)
    val unitPrice = decimal("unit_price", 19, 6)
    val quantity = decimal("quantity", 19, 6)
    val amount = decimal("amount", 19, 6)
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

    var tenantId by BillingPricingEvidence.tenantId
    var meterCode by BillingPricingEvidence.meterCode
    var currency by BillingPricingEvidence.currency
    var unitPrice by BillingPricingEvidence.unitPrice
    var effectiveAt by BillingPricingEvidence.effectiveAt
}

class BillingPriceEvidenceInboxEventEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<BillingPriceEvidenceInboxEventEntity>(BillingPriceEvidenceInboxEvents)

    var eventId by BillingPriceEvidenceInboxEvents.eventId
    var tenantId by BillingPriceEvidenceInboxEvents.tenantId
    var payloadDigest by BillingPriceEvidenceInboxEvents.payloadDigest
    var createdAt by BillingPriceEvidenceInboxEvents.createdAt
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
    var currency by BillingCharges.currency
    var unitPrice by BillingCharges.unitPrice
    var quantity by BillingCharges.quantity
    var amount by BillingCharges.amount
    var createdAt by BillingCharges.createdAt
}

@Suppress("AbstractClassCanBeConcreteClass")
abstract class BillingExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(ExposedEntityInformationImpl(domainClass))

@Suppress("AbstractClassCanBeConcreteClass")
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
class ExposedBillingAdjustmentJournal : BillingAdjustmentJournal {
    override fun post(command: BillingAdjustmentCommand): BillingAdjustmentOutcome {
        BillingOutboxEventEntity.find { BillingOutboxEvents.eventId eq command.adjustmentEventId }
            .firstOrNull()
            ?.let { return BillingAdjustmentOutcome.DUPLICATE }
        val original = requireNotNull(
            BillingOutboxEventEntity.find {
                (BillingOutboxEvents.eventId eq command.correctionOf) and
                    (BillingOutboxEvents.tenantId eq command.tenantId) and
                    (BillingOutboxEvents.eventType eq "ChargeRated")
            }.firstOrNull(),
        ) { "original_charge_event_not_found:${command.correctionOf}" }
        val now = Instant.now()
        val nextVersion = BillingOutboxEventEntity.find {
            (BillingOutboxEvents.tenantId eq command.tenantId) and
                (BillingOutboxEvents.aggregateId eq original.aggregateId)
        }.count() + 1L
        val envelope = BillingIntegrationEnvelope.create(
            eventId = command.adjustmentEventId,
            eventType = "AdjustmentPosted",
            schemaVersion = 1,
            tenantId = command.tenantId,
            aggregateId = original.aggregateId,
            aggregateVersion = nextVersion,
            payload = """{"correctionOf":"${command.correctionOf}",""" +
                """"currency":"${command.currency}","amount":"${command.amount}"}""",
            occurredAt = now,
            recordedAt = now,
        )
        BillingOutboxEventEntity.new {
            eventId = command.adjustmentEventId
            tenantId = command.tenantId
            eventType = envelope.eventType
            aggregateType = envelope.aggregateType
            aggregateId = original.aggregateId
            aggregateVersion = nextVersion
            partitionKey = envelope.partitionKey()
            payload = envelope.wirePayload()
            payloadDigest = envelope.wirePayloadDigest()
            status = BillingOutboxStatus.PENDING.name
            attempt = 0
            nextAttemptAt = null
            claimOwner = null
            claimUntil = null
            createdAt = now
            updatedAt = now
        }
        return BillingAdjustmentOutcome.APPLIED
    }
}

@Repository
class BillingPricingEvidenceRepository :
    AppendOnlyBillingExposedJdbcRepository<BillingPricingEvidenceEntity, UUID>(
        BillingPricingEvidenceEntity::class.java,
    ) {
    fun append(meterCode: String) {
        append(
            BillingPriceEvidence(
                tenantId = "tenant-a",
                meterCode = meterCode,
                currency = "USD",
                unitPrice = java.math.BigDecimal.ONE,
                effectiveAt = Instant.EPOCH,
            ),
        )
    }

    fun append(evidence: BillingPriceEvidence) {
        BillingPricingEvidenceEntity.new {
            tenantId = evidence.tenantId
            meterCode = evidence.meterCode
            currency = evidence.currency
            unitPrice = evidence.unitPrice
            effectiveAt = evidence.effectiveAt
        }
    }
}

@Repository
class BillingPriceEvidenceInboxRepository :
    AppendOnlyBillingExposedJdbcRepository<BillingPriceEvidenceInboxEventEntity, UUID>(
        BillingPriceEvidenceInboxEventEntity::class.java,
    ) {
    fun record(event: BillingPriceEvidenceEvent): BillingPriceEvidenceOutcome {
        val evidence = event.evidence
        val inserted = BillingPriceEvidenceInboxEvents.insertIgnore {
            it[eventId] = event.eventId
            it[tenantId] = evidence.tenantId
            it[payloadDigest] = event.payloadDigest
            it[createdAt] = evidence.effectiveAt
        }.insertedCount == 1
        if (!inserted) {
            val existing = BillingPriceEvidenceInboxEvents.selectAll()
                .where {
                    (BillingPriceEvidenceInboxEvents.tenantId eq evidence.tenantId) and
                        (BillingPriceEvidenceInboxEvents.eventId eq event.eventId)
                }.singleOrNull()
            return when (existing?.get(BillingPriceEvidenceInboxEvents.payloadDigest)) {
                event.payloadDigest -> BillingPriceEvidenceOutcome.DUPLICATE
                else -> BillingPriceEvidenceOutcome.QUARANTINED
            }
        }
        BillingPricingEvidenceEntity.new {
            tenantId = evidence.tenantId
            meterCode = evidence.meterCode
            currency = evidence.currency
            unitPrice = evidence.unitPrice
            effectiveAt = evidence.effectiveAt
        }
        return BillingPriceEvidenceOutcome.APPLIED
    }
}

@Repository
class BillingInboxRepository :
    AppendOnlyBillingExposedJdbcRepository<BillingInboxEventEntity, UUID>(BillingInboxEventEntity::class.java)

@Repository
class ExposedBillingInboxJournal : BillingInboxJournal {
    override val appliedEventIds: Set<UUID>
        get() = BillingInboxEventEntity.find { BillingInboxEvents.status eq "APPLIED" }.map { it.eventId }.toSet()

    override fun priceEvidence(tenantId: String, meterCode: String, currency: String): BillingPriceEvidence? =
        BillingPricingEvidenceEntity.find {
            (BillingPricingEvidence.tenantId eq tenantId) and
                (BillingPricingEvidence.meterCode eq meterCode) and
                (BillingPricingEvidence.currency eq currency)
        }.firstOrNull()?.let { evidence ->
            BillingPriceEvidence(
                tenantId = evidence.tenantId,
                meterCode = evidence.meterCode,
                currency = evidence.currency,
                unitPrice = evidence.unitPrice,
                effectiveAt = evidence.effectiveAt,
            )
        }

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
        val price = checkNotNull(priceEvidence(event.tenantId, event.meterCode, event.currency)) {
            "pricing evidence must exist before rating"
        }
        val amount = price.unitPrice.multiply(event.quantity)
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
            currency = event.currency
            unitPrice = price.unitPrice
            quantity = event.quantity
            this.amount = amount
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
            payload = """{"sourceEventId":"${event.eventId}",""" +
                """"currency":"${event.currency}",""" +
                """"amount":"$amount"}""",
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

@Repository
class ExposedBillingOutboxJournal : BillingOutboxJournal {
    @Transactional
    override fun claim(owner: String, now: Instant, limit: Int): List<BillingOutboxLease> =
        BillingOutboxEvents.selectAll()
            .where { claimableAt(now) }
            .orderBy(BillingOutboxEvents.createdAt to SortOrder.ASC, BillingOutboxEvents.id to SortOrder.ASC)
            .limit(limit)
            .mapNotNull { row ->
                val eventId = row[BillingOutboxEvents.eventId]
                val claimed = BillingOutboxEvents.update({
                    (BillingOutboxEvents.eventId eq eventId) and claimableAt(now)
                }) {
                    it[status] = BillingOutboxStatus.CLAIMED.name
                    it[claimOwner] = owner
                    it[claimUntil] = now.plus(CLAIM_LEASE)
                    it[updatedAt] = now
                }
                if (claimed != 1) return@mapNotNull null
                BillingOutboxEvents.selectAll()
                    .where { BillingOutboxEvents.eventId eq eventId }
                    .singleOrNull()
                    ?.let { claimedRow ->
                        BillingOutboxLease(
                            claimedRow[BillingOutboxEvents.eventId],
                            claimedRow[BillingOutboxEvents.partitionKey],
                            claimedRow[BillingOutboxEvents.payload],
                        )
                    }
            }

    @Transactional
    override fun markPublished(eventId: UUID, owner: String, now: Instant): Boolean =
        BillingOutboxEvents.update({ claimedBy(owner, eventId, now) }) {
            it[status] = BillingOutboxStatus.PUBLISHED.name
            it[claimOwner] = null
            it[claimUntil] = null
            it[nextAttemptAt] = null
            it[updatedAt] = now
        } == 1

    @Transactional
    override fun markRetryWait(eventId: UUID, owner: String, now: Instant): Boolean =
        BillingOutboxEvents.selectAll().where { claimedBy(owner, eventId, now) }.singleOrNull()?.let { row ->
            val attempts = row[BillingOutboxEvents.attempt] + 1
            BillingOutboxEvents.update({ claimedBy(owner, eventId, now) }) {
                it[attempt] = attempts
                it[status] = if (attempts >= MAX_ATTEMPTS) {
                    BillingOutboxStatus.QUARANTINED.name
                } else {
                    BillingOutboxStatus.RETRY_WAIT.name
                }
                it[claimOwner] = null
                it[claimUntil] = null
                it[nextAttemptAt] = now.plus(RETRY_DELAY)
                it[updatedAt] = now
            } == 1
        } ?: false

    private fun claimableAt(now: Instant): Op<Boolean> =
        (BillingOutboxEvents.status eq BillingOutboxStatus.PENDING.name) or
            ((BillingOutboxEvents.status eq BillingOutboxStatus.RETRY_WAIT.name) and
                (BillingOutboxEvents.nextAttemptAt lessEq now)) or
            ((BillingOutboxEvents.status eq BillingOutboxStatus.CLAIMED.name) and
                (BillingOutboxEvents.claimUntil lessEq now))

    private fun claimedBy(owner: String, eventId: UUID, now: Instant): Op<Boolean> =
        (BillingOutboxEvents.eventId eq eventId) and
            (BillingOutboxEvents.status eq BillingOutboxStatus.CLAIMED.name) and
            (BillingOutboxEvents.claimOwner eq owner) and
            (BillingOutboxEvents.claimUntil greater now)

    private companion object {
        val CLAIM_LEASE: Duration = Duration.ofSeconds(30)
        val RETRY_DELAY: Duration = Duration.ofSeconds(1)
        const val MAX_ATTEMPTS = 3
    }
}
