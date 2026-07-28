package io.bluetape4k.workshop.commerce.voucher.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Repository
internal class EventInboxRepository(
    private val gate: DatabasePermitGate,
) : LongAuditableJdbcRepository<EventInboxRecord, EventInboxTable> {
    override val table = EventInboxTable
    override fun extractId(entity: EventInboxRecord): Long = entity.id

    override fun ResultRow.toEntity(): EventInboxRecord =
        EventInboxRecord(
            id = this[table.id].value,
            tenantId = this[table.tenantId],
            eventId = this[table.eventId],
            aggregateType = this[table.aggregateType],
            aggregateId = this[table.aggregateId],
            payloadDigest = this[table.payloadDigest],
            observedSequence = this[table.observedSequence],
            status = this[table.status],
            attempt = this[table.attempt],
            nextAttemptAt = this[table.nextAttemptAt],
            claimOwner = this[table.claimOwner],
            claimUntil = this[table.claimUntil],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt],
        )

    fun insert(record: EventInboxRecord): EventInboxRecord {
        gate.requireHeld()
        val id =
            table.insertAndGetId {
                it[tenantId] = record.tenantId
                it[eventId] = record.eventId
                it[aggregateType] = record.aggregateType
                it[aggregateId] = record.aggregateId
                it[payloadDigest] = record.payloadDigest
                it[observedSequence] = record.observedSequence
                it[status] = record.status
                it[attempt] = record.attempt
                it[nextAttemptAt] = record.nextAttemptAt
                it[claimOwner] = record.claimOwner
                it[claimUntil] = record.claimUntil
            }.value
        return findById(id)
    }

    /** tenant/event id별로 한 번 insert하고, unique-key race 뒤 durable winner를 반환합니다. */
    fun insertIfAbsent(record: EventInboxRecord): InboxInsertResult {
        gate.requireHeld()
        val inserted =
            table.insertIgnore {
                it[tenantId] = record.tenantId
                it[eventId] = record.eventId
                it[aggregateType] = record.aggregateType
                it[aggregateId] = record.aggregateId
                it[payloadDigest] = record.payloadDigest
                it[observedSequence] = record.observedSequence
                it[status] = record.status
                it[attempt] = record.attempt
                it[nextAttemptAt] = record.nextAttemptAt
                it[claimOwner] = record.claimOwner
                it[claimUntil] = record.claimUntil
            }.insertedCount == 1
        val durable = checkNotNull(findEvent(record.tenantId, record.eventId))
        return InboxInsertResult(durable, inserted)
    }

    override fun findById(id: Long): EventInboxRecord {
        gate.requireHeld()
        return table.selectAll().where { table.id eq id }.single().let { with(this) { it.toEntity() } }
    }

    fun findEvent(
        tenantId: String,
        eventId: UUID,
    ): EventInboxRecord? {
        gate.requireHeld()
        return table
            .selectAll()
            .where { (table.tenantId eq tenantId) and (table.eventId eq eventId) }
            .singleOrNull()
            ?.let { with(this) { it.toEntity() } }
    }

    fun findBacklogPage(
        tenantId: String,
        afterId: Long?,
        limit: Int,
    ): List<EventInboxRecord> {
        gate.requireHeld()
        val predicate =
            (table.tenantId eq tenantId) and
                (table.status inList BACKLOG_STATUSES) and
                (afterId?.let { table.id greater it } ?: org.jetbrains.exposed.v1.core.Op.TRUE)
        return table.selectAll()
            .where { predicate }
            .orderBy(table.id to SortOrder.ASC)
            .limit(limit)
            .map { with(this) { it.toEntity() } }
    }

    /** 다른 worker가 이미 소유한 row 뒤에서 기다리지 않고 due row 하나를 claim합니다. */
    fun claimNext(
        now: Instant,
        owner: String,
        lease: Duration,
    ): EventInboxRecord? {
        gate.requireHeld()
        val row =
            table
                .selectAll()
                .where {
                    ((table.status eq InboxStatus.PENDING) and (table.nextAttemptAt lessEq now)) or
                        ((table.status eq InboxStatus.CLAIMED) and (table.claimUntil lessEq now))
                }.orderBy(table.nextAttemptAt to SortOrder.ASC, table.id to SortOrder.ASC)
                .limit(1)
                .forUpdate(
                    ForUpdateOption.PostgreSQL.ForUpdate(
                        ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED,
                    ),
                ).singleOrNull()
                ?: return null
        val id = row[table.id].value
        table.update({ table.id eq id }) {
            it[status] = InboxStatus.CLAIMED
            it[claimOwner] = owner.take(128)
            it[claimUntil] = now.plus(lease)
        }
        return findById(id)
    }

    /** 관련 없는 backlog를 선택하지 않고 방금 insert한 row를 synchronous acceptance 용도로 claim합니다. */
    fun claimById(
        id: Long,
        now: Instant,
        owner: String,
        lease: Duration,
    ): EventInboxRecord {
        gate.requireHeld()
        val row =
            table
                .selectAll()
                .where { (table.id eq id) and (table.status eq InboxStatus.PENDING) }
                .forUpdate()
                .single()
        check(row[table.id].value == id)
        check(
            table.update({ (table.id eq id) and (table.status eq InboxStatus.PENDING) }) {
                it[status] = InboxStatus.CLAIMED
                it[claimOwner] = owner.take(128)
                it[claimUntil] = now.plus(lease)
            } == 1,
        ) { "pending inbox row changed before synchronous claim" }
        return findById(id)
    }

    /** 별도 authority table을 추가하지 않고 한 tenant/aggregate의 decision을 직렬화합니다. */
    fun lockAggregate(record: EventInboxRecord) {
        gate.requireHeld()
        val material = "${record.tenantId}:${record.aggregateType}:${record.aggregateId}"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(StandardCharsets.UTF_8))
        val lockKey = ByteBuffer.wrap(digest).long
        TransactionManager.current().exec("SELECT pg_advisory_xact_lock($lockKey)")
    }

    fun latestAppliedSequence(record: EventInboxRecord): Long? {
        gate.requireHeld()
        return table
            .selectAll()
            .where {
                (table.tenantId eq record.tenantId) and
                    (table.aggregateType eq record.aggregateType) and
                    (table.aggregateId eq record.aggregateId) and
                    (table.status eq InboxStatus.APPLIED)
            }.orderBy(table.observedSequence to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(table.observedSequence)
    }

    fun complete(
        id: Long,
        outcome: InboxStatus,
    ): EventInboxRecord {
        gate.requireHeld()
        require(outcome in TERMINAL_OUTCOMES) { "outcome must be terminal" }
        check(
            table.update({ (table.id eq id) and (table.status eq InboxStatus.CLAIMED) }) {
                it[status] = outcome
                it[claimOwner] = null
                it[claimUntil] = null
            } == 1,
        ) { "claimed inbox row changed before completion" }
        return findById(id)
    }

    fun retry(
        record: EventInboxRecord,
        now: Instant,
        nextAttemptAt: Instant,
        maxAttempts: Int,
    ): EventInboxRecord {
        gate.requireHeld()
        val nextAttempt = record.attempt + 1
        val nextStatus = if (nextAttempt >= maxAttempts) InboxStatus.FAILED else InboxStatus.PENDING
        check(
            table.update({ (table.id eq record.id) and (table.status eq InboxStatus.CLAIMED) }) {
                it[status] = nextStatus
                it[attempt] = nextAttempt
                it[EventInboxTable.nextAttemptAt] = if (nextStatus == InboxStatus.FAILED) now else nextAttemptAt
                it[claimOwner] = null
                it[claimUntil] = null
            } == 1,
        ) { "claimed inbox row changed before retry" }
        return findById(record.id)
    }

    companion object : KLogging() {
        private val TERMINAL_OUTCOMES = setOf(InboxStatus.APPLIED, InboxStatus.IGNORED, InboxStatus.CONFLICT)
        private val BACKLOG_STATUSES = listOf(InboxStatus.PENDING, InboxStatus.CLAIMED, InboxStatus.FAILED)
    }
}

internal data class InboxInsertResult(
    val record: EventInboxRecord,
    val inserted: Boolean,
)
