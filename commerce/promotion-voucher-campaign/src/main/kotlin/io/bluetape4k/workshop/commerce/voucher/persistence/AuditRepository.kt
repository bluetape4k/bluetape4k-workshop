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

/** Append-only audit repository with tenant and aggregate revision uniqueness. */
@Repository
internal class AuditRepository(
    private val gate: DatabasePermitGate,
) : LongAuditableJdbcRepository<AuditRecord, AuditTable> {
    override val table = AuditTable
    override fun extractId(entity: AuditRecord): Long = entity.id

    override fun ResultRow.toEntity(): AuditRecord =
        AuditRecord(
            id = this[table.id].value,
            tenantId = this[table.tenantId],
            campaignId = this[table.campaignId],
            aggregateType = this[table.aggregateType],
            aggregateId = this[table.aggregateId],
            revision = this[table.revision],
            actorType = this[table.actorType],
            reasonCode = this[table.reasonCode],
            policyVersion = this[table.policyVersion],
            correlationDigest = this[table.correlationDigest],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt],
        )

    fun append(record: AuditRecord): AuditRecord {
        gate.requireHeld()
        val id =
            table.insertAndGetId {
                it[tenantId] = record.tenantId
                it[campaignId] = record.campaignId
                it[aggregateType] = record.aggregateType
                it[aggregateId] = record.aggregateId
                it[revision] = record.revision
                it[actorType] = record.actorType
                it[reasonCode] = record.reasonCode
                it[policyVersion] = record.policyVersion
                it[correlationDigest] = record.correlationDigest
            }.value
        return findById(id)
    }

    override fun findById(id: Long): AuditRecord {
        gate.requireHeld()
        return table.selectAll().where { table.id eq id }.single().let { with(this) { it.toEntity() } }
    }

    fun findAggregate(
        tenantId: String,
        aggregateId: UUID,
    ): List<AuditRecord> {
        gate.requireHeld()
        return table
            .selectAll()
            .where { (table.tenantId eq tenantId) and (table.aggregateId eq aggregateId) }
            .map { with(this) { it.toEntity() } }
    }

    companion object : KLogging()
}
