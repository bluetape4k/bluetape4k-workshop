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
internal class ReviewRepository(
    private val gate: DatabasePermitGate,
) : LongAuditableJdbcRepository<ReviewRecord, ReviewTable> {
    override val table = ReviewTable
    override fun extractId(entity: ReviewRecord): Long = entity.id

    override fun ResultRow.toEntity(): ReviewRecord =
        ReviewRecord(
            id = this[table.id].value,
            tenantId = this[table.tenantId],
            campaignId = this[table.campaignId],
            claimRowId = this[table.claimRowId].value,
            claimId = this[table.claimId],
            kind = this[table.kind],
            status = this[table.status],
            reasonCode = this[table.reasonCode],
            signalSummary = this[table.signalSummary],
            reviewerActorDigest = this[table.reviewerActorDigest],
            expectedClaimRevision = this[table.expectedClaimRevision],
            revision = this[table.revision],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt],
        )

    fun insert(record: ReviewRecord): ReviewRecord {
        gate.requireHeld()
        val id =
            table.insertAndGetId {
                it[tenantId] = record.tenantId
                it[campaignId] = record.campaignId
                it[claimRowId] = record.claimRowId
                it[claimId] = record.claimId
                it[kind] = record.kind
                it[status] = record.status
                it[reasonCode] = record.reasonCode
                it[signalSummary] = record.signalSummary
                it[reviewerActorDigest] = record.reviewerActorDigest
                it[expectedClaimRevision] = record.expectedClaimRevision
                it[revision] = record.revision
            }.value
        return findById(id)
    }

    override fun findById(id: Long): ReviewRecord {
        gate.requireHeld()
        return table.selectAll().where { table.id eq id }.single().let { with(this) { it.toEntity() } }
    }

    fun findOpen(
        tenantId: String,
        claimId: UUID,
    ): ReviewRecord? {
        gate.requireHeld()
        return table
            .selectAll()
            .where {
                (table.tenantId eq tenantId) and
                    (table.claimId eq claimId) and
                    (table.status eq ReviewStatus.OPEN)
            }.singleOrNull()
            ?.let { with(this) { it.toEntity() } }
    }

    fun findOpenForUpdate(
        tenantId: String,
        claimId: UUID,
        reviewId: Long,
    ): ReviewRecord? {
        gate.requireHeld()
        return table
            .selectAll()
            .where {
                (table.tenantId eq tenantId) and
                    (table.claimId eq claimId) and
                    (table.id eq reviewId) and
                    (table.status eq ReviewStatus.OPEN)
            }.forUpdate()
            .singleOrNull()
            ?.let { with(this) { it.toEntity() } }
    }

    fun decide(
        review: ReviewRecord,
        status: ReviewStatus,
        reviewerActorDigest: String,
        expectedRevision: Long,
    ): Boolean {
        gate.requireHeld()
        require(status != ReviewStatus.OPEN) { "review decision must be terminal" }
        return auditedUpdateAll(
            predicate = {
                (table.tenantId eq review.tenantId) and
                    (table.id eq review.id) and
                    (table.status eq ReviewStatus.OPEN) and
                    (table.revision eq expectedRevision)
            },
        ) {
            it[ReviewTable.status] = status
            it[ReviewTable.reviewerActorDigest] = reviewerActorDigest
            it[revision] = expectedRevision + 1
        } == 1
    }

    companion object : KLogging()
}
