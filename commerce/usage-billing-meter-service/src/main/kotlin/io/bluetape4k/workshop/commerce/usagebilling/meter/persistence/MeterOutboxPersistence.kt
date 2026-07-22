@file:Suppress("MagicNumber") // SQL column sizes are schema declarations.

package io.bluetape4k.workshop.commerce.usagebilling.meter.persistence

import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterCommandJournal
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterCommandReceipt
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterOutboxRecord
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterPriceVersion
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterOutboxJournal
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterOutboxLease
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterOutboxStatus
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
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
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

object MeterPriceVersions : UUIDTable("meter_price_version", "price_version_id") {
    val tenantId = varchar("tenant_id", 64)
    val meterCode = varchar("meter_code", 64)
    val currency = varchar("currency", 3)
    val unitPrice = decimal("unit_price", 19, 6)
    val effectiveAt = timestamp("effective_at")
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(tenantId, meterCode, currency, effectiveAt)
    }
}

object MeterCommandReceipts : UUIDTable("meter_command_receipt", "receipt_id") {
    val idempotencyKey = varchar("idempotency_key", 128)
    val fingerprint = varchar("fingerprint", 64)
    val priceVersionId = javaUUID("price_version_id")
    val eventId = javaUUID("event_id")
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(idempotencyKey)
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

class MeterPriceVersionEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<MeterPriceVersionEntity>(MeterPriceVersions)

    var tenantId by MeterPriceVersions.tenantId
    var meterCode by MeterPriceVersions.meterCode
    var currency by MeterPriceVersions.currency
    var unitPrice by MeterPriceVersions.unitPrice
    var effectiveAt by MeterPriceVersions.effectiveAt
    var createdAt by MeterPriceVersions.createdAt
}

class MeterCommandReceiptEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<MeterCommandReceiptEntity>(MeterCommandReceipts)

    var idempotencyKey by MeterCommandReceipts.idempotencyKey
    var fingerprint by MeterCommandReceipts.fingerprint
    var priceVersionId by MeterCommandReceipts.priceVersionId
    var eventId by MeterCommandReceipts.eventId
    var createdAt by MeterCommandReceipts.createdAt
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

@Repository
class MeterOutboxRepository :
    AppendOnlyMeterExposedJdbcRepository<MeterOutboxEventEntity, UUID>(MeterOutboxEventEntity::class.java)

@Repository
class MeterPriceVersionRepository :
    AppendOnlyMeterExposedJdbcRepository<MeterPriceVersionEntity, UUID>(MeterPriceVersionEntity::class.java)

@Repository
class MeterCommandReceiptRepository :
    AppendOnlyMeterExposedJdbcRepository<MeterCommandReceiptEntity, UUID>(MeterCommandReceiptEntity::class.java)

@Repository
class ExposedMeterCommandJournal : MeterCommandJournal {
    override fun findReceipt(idempotencyKey: String): MeterCommandReceipt? =
        MeterCommandReceiptEntity.find { MeterCommandReceipts.idempotencyKey eq idempotencyKey }
            .firstOrNull()
            ?.let { entity ->
                MeterCommandReceipt(
                    idempotencyKey = entity.idempotencyKey,
                    fingerprint = entity.fingerprint,
                    result = io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterActivationResult(
                        priceVersionId = entity.priceVersionId,
                        eventId = entity.eventId,
                        replayed = false,
                    ),
                )
            }

    override fun append(
        receipt: MeterCommandReceipt,
        priceVersion: MeterPriceVersion,
        outboxRecord: MeterOutboxRecord,
    ) {
        MeterPriceVersionEntity.new(priceVersion.priceVersionId) {
            tenantId = priceVersion.tenantId
            meterCode = priceVersion.meterCode
            currency = priceVersion.currency
            unitPrice = priceVersion.unitPrice
            effectiveAt = priceVersion.effectiveAt
            createdAt = priceVersion.createdAt
        }
        MeterOutboxEventEntity.new {
            eventId = outboxRecord.eventId
            tenantId = priceVersion.tenantId
            eventType = outboxRecord.eventType
            aggregateType = "Meter"
            aggregateId = priceVersion.meterCode
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
        MeterCommandReceiptEntity.new {
            idempotencyKey = receipt.idempotencyKey
            fingerprint = receipt.fingerprint
            priceVersionId = receipt.result.priceVersionId
            eventId = receipt.result.eventId
            createdAt = outboxRecord.createdAt
        }
    }
}

@Repository
class ExposedMeterOutboxJournal : MeterOutboxJournal {
    @Transactional
    override fun claim(owner: String, now: Instant, limit: Int): List<MeterOutboxLease> =
        MeterOutboxEventEntity.all()
            .filter { event -> event.isClaimable(now) }
            .sortedBy { it.createdAt }
            .take(limit)
            .map { event ->
                event.status = MeterOutboxStatus.CLAIMED.name
                event.claimOwner = owner
                event.claimUntil = now.plus(CLAIM_LEASE)
                event.updatedAt = now
                MeterOutboxLease(event.eventId, event.partitionKey, event.payload, MeterOutboxStatus.CLAIMED, owner)
            }

    @Transactional
    override fun markPublished(eventId: UUID, owner: String, now: Instant): Boolean =
        claimedBy(eventId, owner)?.let { event ->
            event.status = MeterOutboxStatus.PUBLISHED.name
            event.claimOwner = null
            event.claimUntil = null
            event.nextAttemptAt = null
            event.updatedAt = now
            true
        } ?: false

    @Transactional
    override fun markRetryWait(eventId: UUID, owner: String, now: Instant): Boolean =
        claimedBy(eventId, owner)?.let { event ->
            val attempts = event.attempt + 1
            event.attempt = attempts
            event.status = if (attempts >= MAX_ATTEMPTS) {
                MeterOutboxStatus.QUARANTINED.name
            } else {
                MeterOutboxStatus.RETRY_WAIT.name
            }
            event.claimOwner = null
            event.claimUntil = null
            event.nextAttemptAt = now.plus(RETRY_DELAY)
            event.updatedAt = now
            true
        } ?: false

    private fun MeterOutboxEventEntity.isClaimable(now: Instant): Boolean {
        val nextAttempt = nextAttemptAt
        val claimDeadline = claimUntil
        return status == MeterOutboxStatus.PENDING.name ||
            (status == MeterOutboxStatus.RETRY_WAIT.name && (nextAttempt == null || nextAttempt <= now)) ||
            (status == MeterOutboxStatus.CLAIMED.name && claimDeadline != null && claimDeadline <= now)
    }

    private fun claimedBy(eventId: UUID, owner: String): MeterOutboxEventEntity? =
        MeterOutboxEventEntity.find { MeterOutboxEvents.eventId eq eventId }
            .firstOrNull()
            ?.takeIf { it.status == MeterOutboxStatus.CLAIMED.name && it.claimOwner == owner }

    private companion object {
        val CLAIM_LEASE: Duration = Duration.ofSeconds(30)
        val RETRY_DELAY: Duration = Duration.ofSeconds(1)
        const val MAX_ATTEMPTS = 3
    }
}
