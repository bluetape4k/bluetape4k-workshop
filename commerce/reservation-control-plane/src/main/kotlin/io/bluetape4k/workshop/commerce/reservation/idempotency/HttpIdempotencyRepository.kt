package io.bluetape4k.workshop.commerce.reservation.idempotency

import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
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
    val keyDigest: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class IdempotencyRecord(
    val id: Long = 0L,
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

/** Distinguishes a new owner, expired-lease takeover, durable replay, and retry conflicts. */
internal sealed interface AcquireResult : Serializable {
    data class New(
        val record: IdempotencyRecord,
    ) : AcquireResult {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    data class Takeover(
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

/**
 * Owns application-level HTTP idempotency in PostgreSQL.
 *
 * A scoped unique row elects one command owner. Completed responses are replayed, while an expired
 * in-progress lease can be reclaimed only by matching its previous token and deadline in one CAS.
 */
@Repository
internal class HttpIdempotencyRepository :
    LongAuditableJdbcRepository<IdempotencyRecord, HttpIdempotencyTable> {
    override val table = HttpIdempotencyTable

    override fun extractId(entity: IdempotencyRecord): Long = entity.id

    override fun ResultRow.toEntity(): IdempotencyRecord =
        IdempotencyRecord(
            id = this[table.id].value,
            scope = IdempotencyScope(this[table.tenantId], this[table.operation], this[table.keyDigest]),
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
        requestFingerprint: String,
        ownerToken: UUID,
        now: Instant,
        retention: Duration = DEFAULT_RETENTION,
    ): AcquireResult {
        validate(scope, requestFingerprint, retention)

        val inserted =
            table
                .insertIgnore {
                    it[tenantId] = scope.tenantId
                    it[operation] = scope.operation
                    it[keyDigest] = scope.keyDigest
                    it[HttpIdempotencyTable.requestFingerprint] = requestFingerprint
                    it[status] = IdempotencyStatus.IN_PROGRESS
                    it[HttpIdempotencyTable.ownerToken] = ownerToken
                    it[leaseUntil] = now.plus(LEASE_DURATION)
                    it[expiresAt] = now.plus(retention)
                }.insertedCount == 1

        var current = findByScope(scope) ?: error("idempotency record was not persisted")
        if (inserted) {
            log.debug {
                "idempotency_acquired_new operation=${scope.operation} keyDigestPrefix=${scope.keyDigest.take(LOG_DIGEST_PREFIX)}"
            }
            return AcquireResult.New(current)
        }
        if (current.requestFingerprint != requestFingerprint) {
            log.warn {
                "idempotency_fingerprint_conflict operation=${scope.operation} " +
                    "keyDigestPrefix=${scope.keyDigest.take(LOG_DIGEST_PREFIX)}"
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

        // Compare both the previous owner token and lease deadline so only one contender can take over.
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
                it[leaseUntil] = now.plus(LEASE_DURATION)
                it[expiresAt] = now.plus(retention)
            } == 1

        current = findByScope(scope) ?: error("idempotency record disappeared")
        return if (reclaimed) {
            log.warn {
                "idempotency_acquired_takeover operation=${scope.operation} " +
                    "keyDigestPrefix=${scope.keyDigest.take(LOG_DIGEST_PREFIX)}"
            }
            AcquireResult.Takeover(current)
        } else {
            AcquireResult.InProgress(Duration.between(now, current.leaseUntil).coerceAtLeast(Duration.ZERO))
        }
    }

    fun finalize(
        id: Long,
        ownerToken: UUID,
        responseStatus: Int,
        responseBody: String,
        failed: Boolean,
    ): Boolean =
        (auditedUpdateAll(
            predicate = {
                (table.id eq id) and
                    (table.ownerToken eq ownerToken) and
                    (table.status eq IdempotencyStatus.IN_PROGRESS)
            }
        ) {
            it[status] = if (failed) IdempotencyStatus.FAILED else IdempotencyStatus.SUCCEEDED
            it[HttpIdempotencyTable.responseStatus] = responseStatus
            it[HttpIdempotencyTable.responseBody] = responseBody.take(MAX_RESPONSE_BODY)
        } == 1).also { finalized ->
            log.debug {
                "idempotency_finalized id=$id responseStatus=$responseStatus failed=$failed finalized=$finalized"
            }
        }

    fun findByScope(scope: IdempotencyScope): IdempotencyRecord? =
        table
            .selectAll()
            .where {
                (table.tenantId eq scope.tenantId) and
                    (table.operation eq scope.operation) and
                    (table.keyDigest eq scope.keyDigest)
            }.firstOrNull()
            ?.let { with(this) { it.toEntity() } }

    private fun validate(scope: IdempotencyScope, requestFingerprint: String, retention: Duration) {
        require(scope.tenantId.length in 1..80) { "tenantId must contain 1..80 characters" }
        require(scope.operation.length in 1..80) { "operation must contain 1..80 characters" }
        require(scope.keyDigest.length == SHA256_HEX_LENGTH) { "keyDigest must be a SHA-256 hex digest" }
        require(requestFingerprint.length == SHA256_HEX_LENGTH) {
            "requestFingerprint must be a SHA-256 hex digest"
        }
        require(retention > LEASE_DURATION) { "retention must outlive the idempotency lease" }
    }

    companion object : KLogging() {
        val LEASE_DURATION: Duration = Duration.ofSeconds(90)
        val DEFAULT_RETENTION: Duration = Duration.ofHours(24)
        const val MAX_RESPONSE_BODY: Int = 64 * 1024
        private const val SHA256_HEX_LENGTH: Int = 64
        private const val LOG_DIGEST_PREFIX: Int = 12
    }
}
