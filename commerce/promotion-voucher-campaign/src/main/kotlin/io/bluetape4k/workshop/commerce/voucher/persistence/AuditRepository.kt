package io.bluetape4k.workshop.commerce.voucher.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
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

    fun findCursor(id: Long): AuditRecord? {
        gate.requireHeld()
        return table.selectAll().where { table.id eq id }.singleOrNull()?.let { with(this) { it.toEntity() } }
    }

    fun firstCampaignAudit(
        tenantId: String,
        campaignId: UUID,
    ): AuditRecord? {
        gate.requireHeld()
        return campaignQuery(tenantId, campaignId)
            .orderBy(table.id to SortOrder.ASC)
            .limit(1)
            .singleOrNull()
            ?.let { with(this) { it.toEntity() } }
    }

    fun lastCampaignAudit(
        tenantId: String,
        campaignId: UUID,
    ): AuditRecord? {
        gate.requireHeld()
        return campaignQuery(tenantId, campaignId)
            .orderBy(table.id to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let { with(this) { it.toEntity() } }
    }

    /** Reads one bounded campaign stream page in globally monotonic audit-id order. */
    fun findCampaignAfter(
        tenantId: String,
        campaignId: UUID,
        afterId: Long,
        limit: Int,
    ): List<AuditRecord> {
        gate.requireHeld()
        require(limit in 1..MAX_STREAM_BATCH) { "stream audit limit must be between 1 and $MAX_STREAM_BATCH" }
        return table
            .selectAll()
            .where {
                (table.tenantId eq tenantId) and
                    (table.campaignId eq campaignId) and
                    (table.id greater afterId)
            }.orderBy(table.id to SortOrder.ASC)
            .limit(limit)
            .map { with(this) { it.toEntity() } }
    }

    fun hasReason(
        tenantId: String,
        aggregateId: UUID,
        reasonCode: String,
    ): Boolean {
        gate.requireHeld()
        return table
            .selectAll()
            .where {
                (table.tenantId eq tenantId) and
                    (table.aggregateId eq aggregateId) and
                    (table.reasonCode eq reasonCode)
            }.limit(1)
            .any()
    }

    private fun campaignQuery(
        tenantId: String,
        campaignId: UUID,
    ) = table.selectAll().where { (table.tenantId eq tenantId) and (table.campaignId eq campaignId) }

    companion object : KLogging() {
        private const val MAX_STREAM_BATCH = 200
    }
}
