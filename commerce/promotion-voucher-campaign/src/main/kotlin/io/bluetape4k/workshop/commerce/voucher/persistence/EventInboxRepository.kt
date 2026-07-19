package io.bluetape4k.workshop.commerce.voucher.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
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

    companion object : KLogging()
}
