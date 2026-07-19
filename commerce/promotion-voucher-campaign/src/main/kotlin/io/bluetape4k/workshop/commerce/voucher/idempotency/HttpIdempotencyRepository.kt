package io.bluetape4k.workshop.commerce.voucher.idempotency

import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable
import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal enum class IdempotencyStatus {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
}

/** Closed response variants are persisted instead of arbitrary serialized bodies. */
internal enum class VoucherResponseKind {
    CAMPAIGN_CREATED,
    CAMPAIGN_ACTIVATED,
    CAMPAIGN_PAUSE_ACCEPTED,
    CAMPAIGN_END_ACCEPTED,
    CAMPAIGN_POLICY_UPDATED,
    ALLOCATION_ACCEPTED,
    ALLOCATION_REVIEW_REQUIRED,
    REDEMPTION_ACCEPTED,
    REDEMPTION_REVIEW_REQUIRED,
    CLAIM_RELEASED,
    CLAIM_REVOCATION_ACCEPTED,
    CODE_ACKNOWLEDGED,
    REVIEW_APPROVED,
    REVIEW_REJECTED,
    FIXTURE_CONFIGURED,
    RECONCILIATION_COMPLETED,
    RECONCILIATION_IN_PROGRESS,
    RATE_LIMITED,
    DATABASE_BULKHEAD_REJECTED,
    AUTHORITATIVE_BACKEND_UNAVAILABLE,
    CAMPAIGN_PAUSED,
    CAMPAIGN_ENDED,
    CLAIM_EXPIRED,
    CLAIM_REVOKED,
    ALREADY_REDEEMED,
    CAPACITY_EXHAUSTED,
    PER_USER_LIMIT_REACHED,
    STALE_REVISION,
    CAMPAIGN_ALREADY_EXISTS,
    CAMPAIGN_NOT_FOUND,
    CLAIM_NOT_FOUND,
    REVIEW_NOT_FOUND,
    CAMPAIGN_NOT_ACTIVE,
    CAMPAIGN_NOT_STARTED,
    INVALID_CODE,
    CONCURRENT_MODIFICATION,
    CODE_ALREADY_ACKNOWLEDGED,
    IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE,
}

internal data class StoredHttpResponse(
    val responseKind: VoucherResponseKind,
    val status: Int,
    val headers: Map<String, String>,
    val aggregateId: UUID,
    val allocationId: UUID?,
    val aggregateRevision: Long,
    val generationKeyVersion: Int?,
    val verificationKeyVersion: Int?,
) : Serializable {
    init {
        require(status in 100..599) { "response status must be a valid HTTP status" }
        require(aggregateRevision >= 0) { "aggregateRevision must not be negative" }
        require(headers.size <= MAX_HEADERS) { "too many stored response headers" }
        require(headers.keys.all { it in ALLOWED_RESPONSE_HEADERS }) { "unsupported stored response header" }
        require((generationKeyVersion == null) == (verificationKeyVersion == null)) {
            "generation and verification key versions must be stored together"
        }
    }

    companion object {
        private const val MAX_HEADERS = 4
        private val ALLOWED_RESPONSE_HEADERS =
            setOf(
                "Content-Type",
                "ETag",
                "Location",
                "Retry-After",
                "X-Workshop-Campaign-Descriptor",
                "X-Workshop-Claim-Descriptor",
                "X-Workshop-Allocation-Descriptor",
                "X-Workshop-Reconciliation-Result",
            )
        private const val serialVersionUID: Long = 1L
    }
}

internal data class IdempotencyScope(
    val tenantId: String,
    val principalDigest: Digest,
    val operation: String,
    val resourceId: String,
    val keyDigest: Digest,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class IdempotencyRecord(
    val id: Long,
    val scope: IdempotencyScope,
    val requestFingerprint: Digest,
    val status: IdempotencyStatus,
    val ownerTokenDigest: Digest?,
    val leaseUntil: Instant?,
    val commandDeadline: Instant,
    val response: StoredHttpResponse?,
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

internal sealed interface IdempotencyAcquireResult : Serializable {
    data class Owner(
        val ownerToken: OwnerToken,
        val leaseUntil: Instant,
    ) : IdempotencyAcquireResult

    data class Replay(
        val response: StoredHttpResponse,
    ) : IdempotencyAcquireResult

    data class InProgress(
        val retryAfter: Duration,
    ) : IdempotencyAcquireResult

    data object FingerprintConflict : IdempotencyAcquireResult
}

internal object HttpIdempotencyTable : AuditableLongIdTable("voucher_http_idempotency") {
    val tenantId = varchar("tenant_id", 64)
    val principalDigest = char("principal_digest", 43)
    val operation = varchar("operation", 64)
    val resourceId = varchar("resource_id", 128)
    val keyDigest = char("key_digest", 43)
    val requestFingerprint = char("request_fingerprint", 43)
    val status = enumerationByName<IdempotencyStatus>("status", 24)
    val ownerTokenDigest = char("owner_token_digest", 43).nullable()
    val leaseUntil = timestamp("lease_until").nullable()
    val commandDeadline = timestamp("command_deadline")
    val responseKind = enumerationByName<VoucherResponseKind>("response_kind", 48).nullable()
    val responseStatus = integer("response_status").nullable()
    val responseHeaders = varchar("response_headers", 512).nullable()
    val aggregateId = javaUUID("aggregate_id").nullable()
    val allocationId = javaUUID("allocation_id").nullable()
    val aggregateRevision = long("aggregate_revision").nullable()
    val generationKeyVersion = integer("generation_key_version").nullable()
    val verificationKeyVersion = integer("verification_key_version").nullable()
    val expiresAt = timestamp("expires_at")

    init {
        uniqueIndex(tenantId, principalDigest, operation, resourceId, keyDigest)
        index(false, status, expiresAt, id)
    }
}

internal interface VoucherIdempotencyStore {
    /** Returns only terminal replay/conflict state; an in-progress row is handled by [acquire]. */
    fun lookup(
        scope: IdempotencyScope,
        fingerprint: Digest,
    ): IdempotencyAcquireResult?

    fun acquire(
        scope: IdempotencyScope,
        fingerprint: Digest,
        now: Instant,
        ownerToken: OwnerToken = OwnerToken.random(),
        lease: Duration = HttpIdempotencyRepository.DEFAULT_LEASE,
        commandTimeout: Duration = HttpIdempotencyRepository.DEFAULT_COMMAND_TIMEOUT,
        retention: Duration = HttpIdempotencyRepository.DEFAULT_RETENTION,
    ): IdempotencyAcquireResult

    fun isOwner(
        scope: IdempotencyScope,
        ownerToken: OwnerToken,
        now: Instant,
    ): Boolean

    fun finalize(
        scope: IdempotencyScope,
        ownerToken: OwnerToken,
        now: Instant,
        response: StoredHttpResponse,
    ): Boolean

    fun release(
        scope: IdempotencyScope,
        ownerToken: OwnerToken,
    ): Boolean

    fun find(scope: IdempotencyScope): IdempotencyRecord?

    fun cleanupExpired(
        now: Instant,
        limit: Int,
    ): Int
}

/** PostgreSQL authority for scoped HTTP idempotency, lease takeover, and terminal replay. */
@Repository
internal class HttpIdempotencyRepository(
    private val gate: DatabasePermitGate,
) : LongAuditableJdbcRepository<IdempotencyRecord, HttpIdempotencyTable>,
    VoucherIdempotencyStore {
    override val table = HttpIdempotencyTable

    override fun extractId(entity: IdempotencyRecord): Long = entity.id

    override fun ResultRow.toEntity(): IdempotencyRecord {
        val persistedStatus = this[table.status]
        val response =
            if (persistedStatus == IdempotencyStatus.IN_PROGRESS) {
                null
            } else {
                StoredHttpResponse(
                    responseKind = requireNotNull(this[table.responseKind]),
                    status = requireNotNull(this[table.responseStatus]),
                    headers = decodeHeaders(requireNotNull(this[table.responseHeaders])),
                    aggregateId = requireNotNull(this[table.aggregateId]),
                    allocationId = this[table.allocationId],
                    aggregateRevision = requireNotNull(this[table.aggregateRevision]),
                    generationKeyVersion = this[table.generationKeyVersion],
                    verificationKeyVersion = this[table.verificationKeyVersion],
                )
            }
        return IdempotencyRecord(
            id = this[table.id].value,
            scope =
                IdempotencyScope(
                    tenantId = this[table.tenantId],
                    principalDigest = Digest.of(decodeBase64Url(this[table.principalDigest])),
                    operation = this[table.operation],
                    resourceId = this[table.resourceId],
                    keyDigest = Digest.of(decodeBase64Url(this[table.keyDigest])),
                ),
            requestFingerprint = Digest.of(decodeBase64Url(this[table.requestFingerprint])),
            status = persistedStatus,
            ownerTokenDigest = this[table.ownerTokenDigest]?.let { Digest.of(decodeBase64Url(it)) },
            leaseUntil = this[table.leaseUntil],
            commandDeadline = this[table.commandDeadline],
            response = response,
            expiresAt = this[table.expiresAt],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt],
        )
    }

    override fun lookup(
        scope: IdempotencyScope,
        fingerprint: Digest,
    ): IdempotencyAcquireResult? {
        gate.requireHeld()
        val current = find(scope) ?: return null
        if (current.requestFingerprint != fingerprint) return IdempotencyAcquireResult.FingerprintConflict
        return current.response?.let(IdempotencyAcquireResult::Replay)
    }

    override fun acquire(
        scope: IdempotencyScope,
        fingerprint: Digest,
        now: Instant,
        ownerToken: OwnerToken,
        lease: Duration,
        commandTimeout: Duration,
        retention: Duration,
    ): IdempotencyAcquireResult {
        gate.requireHeld()
        validate(scope, lease, commandTimeout, retention)
        val ownerDigest = ownerToken.digest()
        val leaseUntil = now.plus(lease)
        val inserted =
            table.insertIgnore {
                it[tenantId] = scope.tenantId
                it[principalDigest] = scope.principalDigest.base64Url
                it[operation] = scope.operation
                it[resourceId] = scope.resourceId
                it[keyDigest] = scope.keyDigest.base64Url
                it[requestFingerprint] = fingerprint.base64Url
                it[status] = IdempotencyStatus.IN_PROGRESS
                it[ownerTokenDigest] = ownerDigest.base64Url
                it[HttpIdempotencyTable.leaseUntil] = leaseUntil
                it[commandDeadline] = now.plus(commandTimeout)
                it[expiresAt] = now.plus(retention)
            }.insertedCount == 1

        var current = find(scope) ?: error("idempotency row was not persisted")
        if (inserted) {
            log.debug { "voucher_idempotency_acquired scope=${scope.logScope()}" }
            return IdempotencyAcquireResult.Owner(ownerToken, leaseUntil)
        }
        if (current.requestFingerprint != fingerprint) {
            log.warn { "voucher_idempotency_fingerprint_conflict scope=${scope.logScope()}" }
            return IdempotencyAcquireResult.FingerprintConflict
        }
        current.response?.let { return IdempotencyAcquireResult.Replay(it) }
        val currentLease = requireNotNull(current.leaseUntil)
        if (currentLease.isAfter(now)) {
            return IdempotencyAcquireResult.InProgress(Duration.between(now, currentLease))
        }

        val previousOwner = requireNotNull(current.ownerTokenDigest)
        val reclaimed =
            auditedUpdateAll(
                predicate = {
                    (table.id eq current.id) and
                        (table.status eq IdempotencyStatus.IN_PROGRESS) and
                        (table.ownerTokenDigest eq previousOwner.base64Url) and
                        (table.leaseUntil eq currentLease)
                },
            ) {
                it[ownerTokenDigest] = ownerDigest.base64Url
                it[HttpIdempotencyTable.leaseUntil] = leaseUntil
                it[commandDeadline] = now.plus(commandTimeout)
                it[expiresAt] = now.plus(retention)
            } == 1
        if (reclaimed) {
            log.warn { "voucher_idempotency_lease_taken_over scope=${scope.logScope()}" }
            return IdempotencyAcquireResult.Owner(ownerToken, leaseUntil)
        }

        current = find(scope) ?: error("idempotency row disappeared")
        current.response?.let { return IdempotencyAcquireResult.Replay(it) }
        return IdempotencyAcquireResult.InProgress(
            Duration.between(now, requireNotNull(current.leaseUntil)).coerceAtLeast(Duration.ZERO),
        )
    }

    override fun isOwner(
        scope: IdempotencyScope,
        ownerToken: OwnerToken,
        now: Instant,
    ): Boolean {
        gate.requireHeld()
        val ownerDigest = ownerToken.digest().base64Url
        return table
            .selectAll()
            .where {
                scopePredicate(scope) and
                    (table.status eq IdempotencyStatus.IN_PROGRESS) and
                    (table.ownerTokenDigest eq ownerDigest) and
                    (table.leaseUntil greaterEq now) and
                    (table.commandDeadline greaterEq now)
            }.count() == 1L
    }

    override fun finalize(
        scope: IdempotencyScope,
        ownerToken: OwnerToken,
        now: Instant,
        response: StoredHttpResponse,
    ): Boolean {
        gate.requireHeld()
        val encodedHeaders = encodeHeaders(response.headers)
        require(encodedHeaders.length <= MAX_RESPONSE_HEADERS) { "stored response headers are too large" }
        val finalized =
            auditedUpdateAll(
                predicate = {
                    scopePredicate(scope) and
                        (table.status eq IdempotencyStatus.IN_PROGRESS) and
                        (table.ownerTokenDigest eq ownerToken.digest().base64Url) and
                        (table.leaseUntil greaterEq now) and
                        (table.commandDeadline greaterEq now)
                },
            ) {
                it[status] =
                    if (response.status < 400) IdempotencyStatus.SUCCEEDED else IdempotencyStatus.FAILED
                it[responseKind] = response.responseKind
                it[responseStatus] = response.status
                it[responseHeaders] = encodedHeaders
                it[aggregateId] = response.aggregateId
                it[allocationId] = response.allocationId
                it[aggregateRevision] = response.aggregateRevision
                it[generationKeyVersion] = response.generationKeyVersion
                it[verificationKeyVersion] = response.verificationKeyVersion
                it[ownerTokenDigest] = null
                it[leaseUntil] = null
            } == 1
        log.debug { "voucher_idempotency_finalized scope=${scope.logScope()} finalized=$finalized" }
        return finalized
    }

    override fun release(
        scope: IdempotencyScope,
        ownerToken: OwnerToken,
    ): Boolean {
        gate.requireHeld()
        val released =
            table.deleteWhere {
                scopePredicate(scope) and
                    (table.status eq IdempotencyStatus.IN_PROGRESS) and
                    (table.ownerTokenDigest eq ownerToken.digest().base64Url)
            } == 1
        log.debug { "voucher_idempotency_owner_released scope=${scope.logScope()} released=$released" }
        return released
    }

    override fun find(scope: IdempotencyScope): IdempotencyRecord? {
        gate.requireHeld()
        return scopedQuery(scope).singleOrNull()?.let { with(this) { it.toEntity() } }
    }

    override fun cleanupExpired(
        now: Instant,
        limit: Int,
    ): Int {
        gate.requireHeld()
        require(limit in 1..MAX_CLEANUP_BATCH) { "cleanup limit must be between 1 and $MAX_CLEANUP_BATCH" }
        val ids =
            table
                .select(table.id)
                .where { (table.status neq IdempotencyStatus.IN_PROGRESS) and (table.expiresAt lessEq now) }
                .orderBy(table.id)
                .limit(limit)
                .map { it[table.id] }
        return if (ids.isEmpty()) 0 else table.deleteWhere { table.id inList ids }
    }

    private fun scopedQuery(scope: IdempotencyScope) =
        table.selectAll().where { scopePredicate(scope) }

    private fun scopePredicate(scope: IdempotencyScope) =
        (table.tenantId eq scope.tenantId) and
            (table.principalDigest eq scope.principalDigest.base64Url) and
            (table.operation eq scope.operation) and
            (table.resourceId eq scope.resourceId) and
            (table.keyDigest eq scope.keyDigest.base64Url)

    private fun validate(
        scope: IdempotencyScope,
        lease: Duration,
        commandTimeout: Duration,
        retention: Duration,
    ) {
        require(scope.tenantId.length in 1..64) { "tenantId must contain 1..64 characters" }
        require(scope.operation.length in 1..64) { "operation must contain 1..64 characters" }
        require(scope.resourceId.length in 1..128) { "resourceId must contain 1..128 characters" }
        require(commandTimeout > Duration.ZERO && commandTimeout < lease) { "command timeout must be shorter than lease" }
        require(retention > lease) { "retention must outlive lease" }
    }

    private fun IdempotencyScope.logScope(): String =
        "operation=$operation resourceId=$resourceId keyDigestPrefix=${keyDigest.base64Url.take(LOG_DIGEST_PREFIX)}"

    companion object : KLogging() {
        val DEFAULT_LEASE: Duration = Duration.ofSeconds(90)
        val DEFAULT_COMMAND_TIMEOUT: Duration = Duration.ofSeconds(60)
        val DEFAULT_RETENTION: Duration = Duration.ofHours(24)
        private const val MAX_RESPONSE_HEADERS = 512
        private const val MAX_CLEANUP_BATCH = 1_000
        private const val LOG_DIGEST_PREFIX = 12
    }
}

private fun encodeHeaders(headers: Map<String, String>): String =
    headers.toSortedMap().entries.joinToString("&") { (name, value) ->
        "${urlEncode(name)}=${urlEncode(value)}"
    }

private fun decodeHeaders(encoded: String): Map<String, String> =
    if (encoded.isEmpty()) {
        emptyMap()
    } else {
        encoded.split('&').associate { pair ->
            val separator = pair.indexOf('=')
            require(separator > 0) { "invalid stored response header descriptor" }
            urlDecode(pair.substring(0, separator)) to urlDecode(pair.substring(separator + 1))
        }
    }

private fun urlEncode(value: String): String =
    buildString {
        value.toByteArray(UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            if (
                unsigned in 'a'.code..'z'.code ||
                unsigned in 'A'.code..'Z'.code ||
                unsigned in '0'.code..'9'.code ||
                unsigned == '-'.code ||
                unsigned == '.'.code ||
                unsigned == '_'.code ||
                unsigned == '~'.code
            ) {
                append(unsigned.toChar())
            } else {
                append('%').append(HEX[unsigned ushr 4]).append(HEX[unsigned and 0x0f])
            }
        }
    }

private fun urlDecode(value: String): String = URLDecoder.decode(value, UTF_8)

private fun decodeBase64Url(value: String): ByteArray = java.util.Base64.getUrlDecoder().decode(value)

private const val HEX = "0123456789ABCDEF"
