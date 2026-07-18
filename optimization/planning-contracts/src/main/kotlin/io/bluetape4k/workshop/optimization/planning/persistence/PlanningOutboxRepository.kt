package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.support.requireNotBlank
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Repository
internal class PlanningOutboxRepository:
    LongAuditableJdbcRepository<PlanningOutboxRecord, PlanningOutboxTable> {

    override val table = PlanningOutboxTable

    override fun extractId(entity: PlanningOutboxRecord) = entity.id

    override fun ResultRow.toEntity() = PlanningOutboxRecord(
        id = this[PlanningOutboxTable.id].value,
        planningRequestId = this[PlanningOutboxTable.planningRequestId],
        payload = this[PlanningOutboxTable.payload],
        status = this[PlanningOutboxTable.status],
        retryCount = this[PlanningOutboxTable.retryCount],
        nextAttemptAt = this[PlanningOutboxTable.nextAttemptAt],
        claimedBy = this[PlanningOutboxTable.claimedBy],
        claimedUntil = this[PlanningOutboxTable.claimedUntil],
        lastErrorCode = this[PlanningOutboxTable.lastErrorCode],
        lastErrorSummary = this[PlanningOutboxTable.lastErrorSummary],
        completedAt = this[PlanningOutboxTable.completedAt],
        createdBy = this[PlanningOutboxTable.createdBy],
        createdAt = this[PlanningOutboxTable.createdAt],
        updatedBy = this[PlanningOutboxTable.updatedBy],
        updatedAt = this[PlanningOutboxTable.updatedAt],
    )

    fun save(record: PlanningOutboxRecord): PlanningOutboxRecord {
        val id = PlanningOutboxTable.insertAndGetId {
            it[planningRequestId] = record.planningRequestId
            it[payload] = record.payload
            it[status] = record.status
            it[retryCount] = record.retryCount
            it[nextAttemptAt] = record.nextAttemptAt
            it[claimedBy] = record.claimedBy
            it[claimedUntil] = record.claimedUntil
            it[lastErrorCode] = record.lastErrorCode
            it[lastErrorSummary] = record.lastErrorSummary
            it[completedAt] = record.completedAt
        }
        return findById(id.value)
    }

    fun findByRequestId(planningRequestId: UUID): PlanningOutboxRecord? =
        PlanningOutboxTable
            .selectAll()
            .where { PlanningOutboxTable.planningRequestId eq planningRequestId }
            .singleOrNull()
            ?.let { row -> with(this) { row.toEntity() } }

    fun claimNextBatch(
        workerId: String,
        batchSize: Int,
        now: Instant,
        leaseDuration: Duration,
    ): List<PlanningOutboxRecord> {
        workerId.requireNotBlank("workerId")
        require(batchSize > 0) { "batchSize must be positive" }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "leaseDuration must be positive" }

        val claimedUntil = now.plus(leaseDuration)
        val candidates = PlanningOutboxTable
            .selectAll()
            .where { eligibleForClaim(now) }
            .orderBy(PlanningOutboxTable.nextAttemptAt to SortOrder.ASC, PlanningOutboxTable.id to SortOrder.ASC)
            .limit(batchSize)

        return candidates.mapNotNull { row ->
            val rowId = row[PlanningOutboxTable.id]
            val updated = auditedUpdateAll(
                predicate = {
                    (PlanningOutboxTable.id eq rowId) and eligibleForClaim(now)
                },
            ) {
                it[status] = PlanningOutboxStatus.CLAIMED
                it[claimedBy] = workerId
                it[PlanningOutboxTable.claimedUntil] = claimedUntil
            }
            if (updated == 1) {
                with(this) {
                    row.toEntity().copy(
                        status = PlanningOutboxStatus.CLAIMED,
                        claimedBy = workerId,
                        claimedUntil = claimedUntil,
                    )
                }
            } else {
                null
            }
        }
    }

    fun markFailure(
        planningRequestId: UUID,
        workerId: String,
        now: Instant,
        retryDelay: Duration,
        maxRetries: Int,
        errorCode: String,
        errorSummary: String,
    ): PlanningOutboxStatus {
        workerId.requireNotBlank("workerId")
        require(maxRetries > 0) { "maxRetries must be positive" }

        val current = findByRequestId(planningRequestId) ?: return PlanningOutboxStatus.FAILED
        val nextRetryCount = current.retryCount + 1
        val nextStatus = if (nextRetryCount >= maxRetries) {
            PlanningOutboxStatus.DEAD_LETTER
        } else {
            PlanningOutboxStatus.FAILED
        }
        val updated = auditedUpdateAll(
            predicate = {
                (PlanningOutboxTable.planningRequestId eq planningRequestId) and
                    (PlanningOutboxTable.claimedBy eq workerId)
            },
        ) {
            it[status] = nextStatus
            it[retryCount] = nextRetryCount
            it[nextAttemptAt] = now.plus(retryDelay)
            it[claimedBy] = null
            it[claimedUntil] = null
            it[lastErrorCode] = errorCode.take(80)
            it[lastErrorSummary] = sanitize(errorSummary)
        }
        return if (updated == 1) nextStatus else PlanningOutboxStatus.FAILED
    }

    fun markCompleted(
        planningRequestId: UUID,
        workerId: String,
        completedAt: Instant,
    ): Boolean = auditedUpdateAll(
        predicate = {
            (PlanningOutboxTable.planningRequestId eq planningRequestId) and
                (PlanningOutboxTable.claimedBy eq workerId)
        },
    ) {
        it[status] = PlanningOutboxStatus.COMPLETED
        it[claimedBy] = null
        it[claimedUntil] = null
        it[lastErrorCode] = null
        it[lastErrorSummary] = null
        it[PlanningOutboxTable.completedAt] = completedAt
    } == 1

    private fun eligibleForClaim(now: Instant): Op<Boolean> {
        val waiting =
            (PlanningOutboxTable.status eq PlanningOutboxStatus.PENDING) or
                (PlanningOutboxTable.status eq PlanningOutboxStatus.FAILED)
        val expiredClaim =
            (PlanningOutboxTable.status eq PlanningOutboxStatus.CLAIMED) and
                (PlanningOutboxTable.claimedUntil less now)
        val leaseAvailable = PlanningOutboxTable.claimedUntil.isNull() or expiredClaim
        return (waiting or expiredClaim) and
            (PlanningOutboxTable.nextAttemptAt lessEq now) and
            leaseAvailable
    }

    private fun sanitize(message: String): String =
        message
            .replace(Regex("(?i)(secret|token|password|credential)[^\\s,;]*"), "[redacted]")
            .take(240)
}
