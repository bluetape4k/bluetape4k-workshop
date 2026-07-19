package io.bluetape4k.workshop.commerce.voucher.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.util.UUID

/** Persists claim verifiers and key versions without ever accepting plaintext voucher codes. */
@Repository
internal class ClaimRepository(
    private val gate: DatabasePermitGate,
) : LongAuditableJdbcRepository<ClaimRecord, ClaimTable> {
    override val table = ClaimTable

    override fun extractId(entity: ClaimRecord): Long = entity.id

    override fun ResultRow.toEntity(): ClaimRecord =
        ClaimRecord(
            id = this[table.id].value,
            tenantId = this[table.tenantId],
            campaignRowId = this[table.campaignRowId].value,
            campaignId = this[table.campaignId],
            claimId = this[table.claimId],
            allocationId = this[table.allocationId],
            userDigest = this[table.userDigest],
            state = this[table.state],
            reviewKind = this[table.reviewKind],
            pendingFromState = this[table.pendingFromState],
            capacityReserved = this[table.capacityReserved],
            allocationPolicyVersion = this[table.allocationPolicyVersion],
            codeVerifier = this[table.codeVerifier],
            generationKeyVersion = this[table.generationKeyVersion],
            verificationKeyVersion = this[table.verificationKeyVersion],
            expiresAt = this[table.expiresAt],
            redemptionReferenceDigest = this[table.redemptionReferenceDigest],
            revision = this[table.revision],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt],
        )

    fun insert(record: ClaimRecord): ClaimRecord {
        gate.requireHeld()
        val id =
            table.insertAndGetId {
                it[tenantId] = record.tenantId
                it[campaignRowId] = record.campaignRowId
                it[campaignId] = record.campaignId
                it[claimId] = record.claimId
                it[allocationId] = record.allocationId
                it[userDigest] = record.userDigest
                it[state] = record.state
                it[reviewKind] = record.reviewKind
                it[pendingFromState] = record.pendingFromState
                it[capacityReserved] = record.capacityReserved
                it[allocationPolicyVersion] = record.allocationPolicyVersion
                it[codeVerifier] = record.codeVerifier
                it[generationKeyVersion] = record.generationKeyVersion
                it[verificationKeyVersion] = record.verificationKeyVersion
                it[expiresAt] = record.expiresAt
                it[redemptionReferenceDigest] = record.redemptionReferenceDigest
                it[revision] = record.revision
            }.value
        log.debug { "voucher_claim_inserted tenant=${record.tenantId} claimId=${record.claimId}" }
        return findById(id)
    }

    override fun findById(id: Long): ClaimRecord {
        gate.requireHeld()
        return table.selectAll().where { table.id eq id }.single().let { with(this) { it.toEntity() } }
    }

    fun findPublic(
        tenantId: String,
        claimId: UUID,
    ): ClaimRecord? {
        gate.requireHeld()
        return table
            .selectAll()
            .where { (table.tenantId eq tenantId) and (table.claimId eq claimId) }
            .singleOrNull()
            ?.let { with(this) { it.toEntity() } }
    }

    fun findByVerifier(
        tenantId: String,
        verifier: ByteArray?,
    ): ClaimRecord? {
        gate.requireHeld()
        if (verifier == null) return null
        return table
            .selectAll()
            .where { (table.tenantId eq tenantId) and (table.codeVerifier eq verifier) }
            .singleOrNull()
            ?.let { with(this) { it.toEntity() } }
    }

    companion object : KLogging()
}
