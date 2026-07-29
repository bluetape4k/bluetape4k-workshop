package io.bluetape4k.workshop.commerce.voucher.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.util.UUID

/** tenant-scoped campaign repository입니다. 모든 entry point는 미리 획득한 DB permit을 요구합니다. */
@Repository
internal class CampaignRepository(
    private val gate: DatabasePermitGate,
) : LongAuditableJdbcRepository<CampaignRecord, CampaignTable> {
    override val table = CampaignTable

    override fun extractId(entity: CampaignRecord): Long = entity.id

    override fun ResultRow.toEntity(): CampaignRecord =
        CampaignRecord(
            id = this[table.id].value,
            tenantId = this[table.tenantId],
            campaignId = this[table.campaignId],
            state = this[table.state],
            startsAt = this[table.startsAt],
            endsAt = this[table.endsAt],
            capacity = this[table.capacity],
            allocatedCount = this[table.allocatedCount],
            perUserLimit = this[table.perUserLimit],
            redemptionTtlSeconds = this[table.redemptionTtlSeconds],
            policyVersion = this[table.policyVersion],
            revision = this[table.revision],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt],
        )

    fun create(record: CampaignRecord): CampaignRecord {
        gate.requireHeld()
        val id =
            table.insertAndGetId {
                it[tenantId] = record.tenantId
                it[campaignId] = record.campaignId
                it[state] = record.state
                it[startsAt] = record.startsAt
                it[endsAt] = record.endsAt
                it[capacity] = record.capacity
                it[allocatedCount] = record.allocatedCount
                it[perUserLimit] = record.perUserLimit
                it[redemptionTtlSeconds] = record.redemptionTtlSeconds
                it[policyVersion] = record.policyVersion
                it[revision] = record.revision
            }.value
        log.debug { "voucher_campaign_created campaignId=${record.campaignId}" }
        return findById(id)
    }

    override fun findById(id: Long): CampaignRecord {
        gate.requireHeld()
        return table.selectAll().where { table.id eq id }.single().let { with(this) { it.toEntity() } }
    }

    fun findPublic(
        tenantId: String,
        campaignId: UUID,
    ): CampaignRecord? {
        gate.requireHeld()
        return table
            .selectAll()
            .where { (table.tenantId eq tenantId) and (table.campaignId eq campaignId) }
            .singleOrNull()
            ?.let { with(this) { it.toEntity() } }
    }

    /** capacity를 변경하는 모든 command에 대해 canonical first row lock을 획득합니다. */
    fun findPublicForUpdate(
        tenantId: String,
        campaignId: UUID,
    ): CampaignRecord? {
        gate.requireHeld()
        return table
            .selectAll()
            .where { (table.tenantId eq tenantId) and (table.campaignId eq campaignId) }
            .forUpdate()
            .singleOrNull()
            ?.let { with(this) { it.toEntity() } }
    }

    fun tryReserve(
        tenantId: String,
        id: Long,
        expectedRevision: Long,
    ): Boolean {
        gate.requireHeld()
        return auditedUpdateAll(
            predicate = {
                (table.tenantId eq tenantId) and
                    (table.id eq id) and
                    (table.state eq CampaignState.ACTIVE) and
                    (table.revision eq expectedRevision) and
                    (table.allocatedCount less table.capacity)
            },
        ) {
            it[allocatedCount] = allocatedCount + 1
            it[revision] = expectedRevision + 1
        }.also { updated ->
            log.debug { "voucher_campaign_reserve campaignRowId=$id revision=$expectedRevision updated=$updated" }
        } == 1
    }

    fun tryRelease(
        tenantId: String,
        id: Long,
        expectedRevision: Long,
    ): Boolean {
        gate.requireHeld()
        return auditedUpdateAll(
            predicate = {
                (table.tenantId eq tenantId) and
                    (table.id eq id) and
                    (table.revision eq expectedRevision) and
                    (table.allocatedCount greater 0)
            },
        ) {
            it[allocatedCount] = allocatedCount - 1
            it[revision] = expectedRevision + 1
        } == 1
    }

    fun transition(
        record: CampaignRecord,
        expectedRevision: Long,
    ): Boolean {
        gate.requireHeld()
        require(record.revision == expectedRevision + 1) { "campaign transition must advance revision exactly once" }
        return auditedUpdateAll(
            predicate = {
                (table.tenantId eq record.tenantId) and
                    (table.id eq record.id) and
                    (table.revision eq expectedRevision)
            },
        ) {
            it[state] = record.state
            it[startsAt] = record.startsAt
            it[endsAt] = record.endsAt
            it[capacity] = record.capacity
            it[allocatedCount] = record.allocatedCount
            it[perUserLimit] = record.perUserLimit
            it[redemptionTtlSeconds] = record.redemptionTtlSeconds
            it[policyVersion] = record.policyVersion
            it[revision] = record.revision
        } == 1
    }

    companion object : KLogging()
}
