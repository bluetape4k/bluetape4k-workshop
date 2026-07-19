package io.bluetape4k.workshop.commerce.order.idempotency

import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.order.persistence.HttpIdempotencyTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal enum class IdempotencyStatus { IN_PROGRESS, SUCCEEDED, FAILED }

internal data class IdempotencyScope(
    val tenantId: String,
    val operation: String,
    val keyHash: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class IdempotencyRecord(
    val id: Long = 0,
    val scope: IdempotencyScope,
    val requestFingerprint: String,
    val status: IdempotencyStatus,
    val ownerToken: UUID,
    val leaseUntil: Instant,
    val responseStatus: Int? = null,
    val responseBody: String? = null,
    val expiresAt: Instant,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable,
    Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal sealed interface AcquireResult : Serializable {
    data class Acquired(
        val record: IdempotencyRecord,
    ) : AcquireResult {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    data class Replay(
        val status: Int,
        val body: String,
        val failed: Boolean,
    ) : AcquireResult {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    data object FingerprintConflict : AcquireResult

    data class InProgress(
        val retryAfter: Duration,
    ) : AcquireResult {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}

@Repository
internal class HttpIdempotencyRepository : LongAuditableJdbcRepository<IdempotencyRecord, HttpIdempotencyTable> {
    override val table = HttpIdempotencyTable

    override fun extractId(entity: IdempotencyRecord) = entity.id

    override fun ResultRow.toEntity() =
        IdempotencyRecord(
            id = this[table.id].value,
            scope = IdempotencyScope(this[table.tenantId], this[table.operation], this[table.keyHash]),
            requestFingerprint = this[table.requestFingerprint],
            status = this[table.status],
            ownerToken = this[table.ownerToken],
            leaseUntil = this[table.leaseUntil],
            responseStatus = this[table.responseStatus],
            responseBody = this[table.responseBody],
            expiresAt = this[table.expiresAt],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    fun acquire(
        scope: IdempotencyScope,
        fingerprint: String,
        ownerToken: UUID,
        now: Instant,
        lease: Duration,
        retention: Duration,
    ): AcquireResult {
        require(scope.tenantId.length in 1..80)
        require(scope.operation.length in 1..80)
        require(scope.keyHash.length == 64)
        require(fingerprint.length == 64)

        val inserted =
            table
                .insertIgnore {
                    it[tenantId] = scope.tenantId
                    it[operation] = scope.operation
                    it[keyHash] = scope.keyHash
                    it[requestFingerprint] = fingerprint
                    it[status] = IdempotencyStatus.IN_PROGRESS
                    it[HttpIdempotencyTable.ownerToken] = ownerToken
                    it[leaseUntil] = now.plus(lease)
                    it[expiresAt] = now.plus(retention)
                }.insertedCount == 1

        var current = findByScope(scope) ?: error("idempotency record was not persisted")
        if (inserted) {
            log.debug {
                "idempotency_record_acquired operation=${scope.operation} keyHashPrefix=${scope.keyHash.take(
                    12
                )}"
            }
            return AcquireResult.Acquired(current)
        }
        if (current.requestFingerprint != fingerprint) {
            log.warn {
                "idempotency_record_conflict operation=${scope.operation} keyHashPrefix=${scope.keyHash.take(
                    12
                )}"
            }
            return AcquireResult.FingerprintConflict
        }
        if (current.status != IdempotencyStatus.IN_PROGRESS) {
            return AcquireResult.Replay(
                status = requireNotNull(current.responseStatus),
                body = requireNotNull(current.responseBody),
                failed = current.status == IdempotencyStatus.FAILED
            )
        }
        if (current.leaseUntil.isAfter(now)) {
            return AcquireResult.InProgress(Duration.between(now, current.leaseUntil))
        }

        val reclaimed =
            auditedUpdateAll(
                predicate = {
                    (table.id eq current.id) and
                        (table.status eq IdempotencyStatus.IN_PROGRESS) and
                        (table.ownerToken eq current.ownerToken) and
                        (table.leaseUntil eq current.leaseUntil)
                }
            ) {
                it[HttpIdempotencyTable.ownerToken] = ownerToken
                it[leaseUntil] = now.plus(lease)
            } == 1

        current = findByScope(scope) ?: error("idempotency record disappeared")
        return if (reclaimed) {
            log.warn {
                "idempotency_lease_reclaimed operation=${scope.operation} keyHashPrefix=${scope.keyHash.take(
                    12
                )}"
            }
            AcquireResult.Acquired(current)
        } else {
            AcquireResult.InProgress(Duration.between(now, current.leaseUntil).coerceAtLeast(Duration.ZERO))
        }
    }

    fun finalize(
        id: Long,
        ownerToken: UUID,
        status: Int,
        body: String,
        failed: Boolean,
    ): Boolean =
        auditedUpdateAll(
            predicate = {
                (table.id eq id) and
                    (table.ownerToken eq ownerToken) and
                    (table.status eq IdempotencyStatus.IN_PROGRESS)
            }
        ) {
            it[HttpIdempotencyTable.status] = if (failed) IdempotencyStatus.FAILED else IdempotencyStatus.SUCCEEDED
            it[responseStatus] = status
            it[responseBody] = body.take(MAX_RESPONSE_BODY)
        }.also { updated ->
            log.debug { "idempotency_record_finalized id=$id status=$status failed=$failed updated=$updated" }
        } == 1

    fun findByScope(scope: IdempotencyScope): IdempotencyRecord? =
        table
            .selectAll()
            .where {
                (table.tenantId eq scope.tenantId) and
                    (table.operation eq scope.operation) and
                    (table.keyHash eq scope.keyHash)
            }.firstOrNull()
            ?.let { with(this) { it.toEntity() } }

    /** Deletes a bounded batch of expired terminal responses while preserving recoverable owners. */
    fun deleteExpiredTerminal(
        now: Instant,
        limit: Int,
    ): Int {
        require(limit in 1..MAX_CLEANUP_BATCH) { "limit must contain 1..$MAX_CLEANUP_BATCH" }
        val candidateIds =
            table
                .selectAll()
                .where {
                    (
                        (table.status eq IdempotencyStatus.SUCCEEDED) or
                            (table.status eq IdempotencyStatus.FAILED)
                    ) and
                        (table.expiresAt lessEq now)
                }.orderBy(table.expiresAt to SortOrder.ASC, table.id to SortOrder.ASC)
                .limit(limit)
                .map { it[table.id] }
        if (candidateIds.isEmpty()) return 0

        return table.deleteWhere { table.id inList candidateIds }.also { deleted ->
            log.debug { "idempotency_terminal_cleanup deleted=$deleted requestedLimit=$limit" }
        }
    }

    companion object : KLogging() {
        const val MAX_RESPONSE_BODY = 64 * 1024
        const val MAX_CLEANUP_BATCH = 1_000
    }
}
