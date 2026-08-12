@file:Suppress("MagicNumber") // SQL column sizes are schema declarations.

package io.bluetape4k.workshop.commerce.usagebilling.usage.persistence

import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidenceInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidenceInboxOutcome
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageAcceptanceJournal
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageOutboxRecord
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageRecord
import io.bluetape4k.workshop.commerce.usagebilling.usage.messaging.UsageOutboxJournal
import io.bluetape4k.workshop.commerce.usagebilling.usage.messaging.UsageOutboxLease
import io.bluetape4k.workshop.commerce.usagebilling.usage.messaging.UsageOutboxStatus
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
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
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

object UsagePriceEvidence : UUIDTable("usage_price_evidence", "price_evidence_id") {
    val tenantId = varchar("tenant_id", 64)
    val meterCode = varchar("meter_code", 64)
    val currency = varchar("currency", 3)
    val unitPrice = decimal("unit_price", 19, 6)
    val effectiveAt = timestamp("effective_at")

    init {
        uniqueIndex(tenantId, meterCode, currency, effectiveAt)
    }
}

object UsagePriceEvidenceInboxEvents : UUIDTable("usage_price_evidence_inbox_event", "price_evidence_inbox_event_id") {
    val eventId = javaUUID("event_id")
    val tenantId = varchar("tenant_id", 64)
    val payloadDigest = varchar("payload_digest", 64)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(tenantId, eventId)
    }
}

object UsageRecords : UUIDTable("usage_record", "usage_id") {
    val tenantId = varchar("tenant_id", 64)
    val sourceSystem = varchar("source_system", 64)
    val sourceEventId = varchar("source_event_id", 128)
    val fingerprint = varchar("fingerprint", 256)
    val eventId = javaUUID("event_id")
    val meterCode = varchar("meter_code", 64)
    val currency = varchar("currency", 3)
    val quantity = decimal("quantity", 19, 6)
    val unitPrice = decimal("unit_price", 19, 6)
    val occurredAt = timestamp("occurred_at")

    init {
        uniqueIndex(tenantId, sourceSystem, sourceEventId)
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

class UsagePriceEvidenceEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<UsagePriceEvidenceEntity>(UsagePriceEvidence)

    var tenantId by UsagePriceEvidence.tenantId
    var meterCode by UsagePriceEvidence.meterCode
    var currency by UsagePriceEvidence.currency
    var unitPrice by UsagePriceEvidence.unitPrice
    var effectiveAt by UsagePriceEvidence.effectiveAt
}

class UsagePriceEvidenceInboxEventEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<UsagePriceEvidenceInboxEventEntity>(UsagePriceEvidenceInboxEvents)

    var eventId by UsagePriceEvidenceInboxEvents.eventId
    var tenantId by UsagePriceEvidenceInboxEvents.tenantId
    var payloadDigest by UsagePriceEvidenceInboxEvents.payloadDigest
    var createdAt by UsagePriceEvidenceInboxEvents.createdAt
}

class UsageRecordEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<UsageRecordEntity>(UsageRecords)

    var tenantId by UsageRecords.tenantId
    var sourceSystem by UsageRecords.sourceSystem
    var sourceEventId by UsageRecords.sourceEventId
    var fingerprint by UsageRecords.fingerprint
    var eventId by UsageRecords.eventId
    var meterCode by UsageRecords.meterCode
    var currency by UsageRecords.currency
    var quantity by UsageRecords.quantity
    var unitPrice by UsageRecords.unitPrice
    var occurredAt by UsageRecords.occurredAt
}

@Suppress("AbstractClassCanBeConcreteClass")
abstract class UsageExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(ExposedEntityInformationImpl(domainClass))

@Suppress("AbstractClassCanBeConcreteClass")
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

@Repository
class UsageOutboxRepository :
    AppendOnlyUsageExposedJdbcRepository<UsageOutboxEventEntity, UUID>(UsageOutboxEventEntity::class.java)

@Repository
class UsagePriceEvidenceRepository :
    AppendOnlyUsageExposedJdbcRepository<UsagePriceEvidenceEntity, UUID>(UsagePriceEvidenceEntity::class.java) {
    fun append(evidence: PriceEvidence) {
        UsagePriceEvidenceEntity.new {
            tenantId = evidence.tenantId
            meterCode = evidence.meterCode
            currency = evidence.currency
            unitPrice = evidence.unitPrice
            effectiveAt = evidence.effectiveAt
        }
    }
}

@Repository
class UsagePriceEvidenceInboxRepository :
    AppendOnlyUsageExposedJdbcRepository<UsagePriceEvidenceInboxEventEntity, UUID>(
        UsagePriceEvidenceInboxEventEntity::class.java,
    ) {
    fun record(event: PriceEvidenceInboxEvent): PriceEvidenceInboxOutcome {
        val inserted =
            UsagePriceEvidenceInboxEvents.insertIgnore {
                it[eventId] = event.eventId
                it[tenantId] = event.tenantId
                it[payloadDigest] = event.payloadDigest
                it[createdAt] = event.evidence.effectiveAt
            }.insertedCount == 1
        if (!inserted) {
            val existing = UsagePriceEvidenceInboxEvents.selectAll()
                .where {
                    (UsagePriceEvidenceInboxEvents.tenantId eq event.tenantId) and
                        (UsagePriceEvidenceInboxEvents.eventId eq event.eventId)
                }.singleOrNull()
            return when (existing?.get(UsagePriceEvidenceInboxEvents.payloadDigest)) {
                event.payloadDigest -> PriceEvidenceInboxOutcome.DUPLICATE
                else -> PriceEvidenceInboxOutcome.QUARANTINED
            }
        }
        UsagePriceEvidenceEntity.new {
            tenantId = event.evidence.tenantId
            meterCode = event.evidence.meterCode
            currency = event.evidence.currency
            unitPrice = event.evidence.unitPrice
            effectiveAt = event.evidence.effectiveAt
        }
        return PriceEvidenceInboxOutcome.APPLIED
    }
}

@Repository
class UsageRecordRepository :
    AppendOnlyUsageExposedJdbcRepository<UsageRecordEntity, UUID>(UsageRecordEntity::class.java)

@Repository
class ExposedUsageAcceptanceJournal : UsageAcceptanceJournal {
    override fun priceEvidence(tenantId: String, meterCode: String, currency: String): PriceEvidence? =
        UsagePriceEvidenceEntity.find {
            (UsagePriceEvidence.tenantId eq tenantId) and
                (UsagePriceEvidence.meterCode eq meterCode) and
                (UsagePriceEvidence.currency eq currency)
        }.firstOrNull()?.let { PriceEvidence(it.tenantId, it.meterCode, it.currency, it.unitPrice, it.effectiveAt) }

    override fun findUsage(tenantId: String, sourceSystem: String, sourceEventId: String): UsageRecord? =
        UsageRecordEntity.find {
            (UsageRecords.tenantId eq tenantId) and
                (UsageRecords.sourceSystem eq sourceSystem) and
                (UsageRecords.sourceEventId eq sourceEventId)
        }.firstOrNull()?.let {
            UsageRecord(
                it.id.value, it.eventId, it.tenantId, it.sourceSystem, it.sourceEventId, it.fingerprint,
                it.meterCode, it.currency, it.quantity, it.unitPrice, it.occurredAt,
            )
        }

    override fun append(usage: UsageRecord, outboxRecord: UsageOutboxRecord) {
        UsageRecordEntity.new(usage.usageId) {
            tenantId = usage.tenantId
            sourceSystem = usage.sourceSystem
            sourceEventId = usage.sourceEventId
            fingerprint = usage.fingerprint
            eventId = usage.eventId
            meterCode = usage.meterCode
            currency = usage.currency
            quantity = usage.quantity
            unitPrice = usage.unitPrice
            occurredAt = usage.occurredAt
        }
        UsageOutboxEventEntity.new {
            eventId = outboxRecord.eventId
            tenantId = usage.tenantId
            eventType = outboxRecord.eventType
            aggregateType = "Usage"
            aggregateId = usage.sourceEventId
            aggregateVersion = 1
            partitionKey = outboxRecord.partitionKey
            payload = outboxRecord.payload
            payloadDigest = outboxRecord.payloadDigest
            status = "PENDING"
            attempt = 0
            nextAttemptAt = null
            claimOwner = null
            claimUntil = null
            createdAt = outboxRecord.createdAt
            updatedAt = outboxRecord.createdAt
        }
    }
}

@Repository
class ExposedUsageOutboxJournal : UsageOutboxJournal {
    @Transactional
    override fun claim(owner: String, now: Instant, limit: Int): List<UsageOutboxLease> =
        UsageOutboxEvents.selectAll()
            .where { claimableAt(now) }
            .orderBy(UsageOutboxEvents.createdAt to SortOrder.ASC, UsageOutboxEvents.id to SortOrder.ASC)
            .limit(limit)
            .mapNotNull { row ->
                val eventId = row[UsageOutboxEvents.eventId]
                val claimed = UsageOutboxEvents.update({
                    (UsageOutboxEvents.eventId eq eventId) and claimableAt(now)
                }) {
                    it[status] = UsageOutboxStatus.CLAIMED.name
                    it[claimOwner] = owner
                    it[claimUntil] = now.plus(CLAIM_LEASE)
                    it[updatedAt] = now
                }
                if (claimed != 1) return@mapNotNull null
                UsageOutboxEvents.selectAll()
                    .where { UsageOutboxEvents.eventId eq eventId }
                    .singleOrNull()
                    ?.let { claimedRow ->
                        UsageOutboxLease(
                            eventId = claimedRow[UsageOutboxEvents.eventId],
                            partitionKey = claimedRow[UsageOutboxEvents.partitionKey],
                            payload = claimedRow[UsageOutboxEvents.payload],
                        )
                    }
            }

    @Transactional
    override fun markPublished(eventId: UUID, owner: String, now: Instant): Boolean =
        UsageOutboxEvents.update({ claimedBy(owner, eventId, now) }) {
            it[status] = UsageOutboxStatus.PUBLISHED.name
            it[claimOwner] = null
            it[claimUntil] = null
            it[nextAttemptAt] = null
            it[updatedAt] = now
        } == 1

    @Transactional
    override fun markRetryWait(eventId: UUID, owner: String, now: Instant): Boolean =
        UsageOutboxEvents.selectAll()
            .where { claimedBy(owner, eventId, now) }
            .singleOrNull()
            ?.let { row ->
                val attempts = row[UsageOutboxEvents.attempt] + 1
                UsageOutboxEvents.update({ claimedBy(owner, eventId, now) }) {
                    it[attempt] = attempts
                    it[status] = if (attempts >= MAX_ATTEMPTS) {
                        UsageOutboxStatus.QUARANTINED.name
                    } else {
                        UsageOutboxStatus.RETRY_WAIT.name
                    }
                    it[claimOwner] = null
                    it[claimUntil] = null
                    it[nextAttemptAt] = now.plus(RETRY_DELAY)
                    it[updatedAt] = now
                } == 1
            } ?: false

    private fun claimableAt(now: Instant): Op<Boolean> =
        (UsageOutboxEvents.status eq UsageOutboxStatus.PENDING.name) or
            ((UsageOutboxEvents.status eq UsageOutboxStatus.RETRY_WAIT.name) and
                (UsageOutboxEvents.nextAttemptAt lessEq now)) or
            ((UsageOutboxEvents.status eq UsageOutboxStatus.CLAIMED.name) and
                (UsageOutboxEvents.claimUntil lessEq now))

    private fun claimedBy(owner: String, eventId: UUID, now: Instant): Op<Boolean> =
        (UsageOutboxEvents.eventId eq eventId) and
            (UsageOutboxEvents.status eq UsageOutboxStatus.CLAIMED.name) and
            (UsageOutboxEvents.claimOwner eq owner) and
            (UsageOutboxEvents.claimUntil greater now)

    private companion object {
        val CLAIM_LEASE: Duration = Duration.ofSeconds(30)
        val RETRY_DELAY: Duration = Duration.ofSeconds(1)
        const val MAX_ATTEMPTS = 3
    }
}
