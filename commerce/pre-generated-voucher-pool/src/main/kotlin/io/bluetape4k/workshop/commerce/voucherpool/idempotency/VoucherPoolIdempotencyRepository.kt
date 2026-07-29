@file:Suppress(
    "LongParameterList", // Decision evaluation keeps its locked row and immutable command authority together.
    "MagicNumber", // PreparedStatement positions mirror the adjacent explicit SQL column order.
    "MaxLineLength",
    "ReturnCount", // Acquire exits immediately for mutually exclusive durable states.
    "TooGenericExceptionCaught", // Jackson may wrap malformed stored JSON in several runtime exception types.
    "TooManyFunctions",
)

package io.bluetape4k.workshop.commerce.voucherpool.idempotency

import io.bluetape4k.jackson3.jsonMapper
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.commerce.voucherpool.domain.DescriptorAction
import io.bluetape4k.workshop.commerce.voucherpool.domain.TombstoneAction
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCatalog
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigest
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.currentOrNull
import java.io.Serializable
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration
import java.util.UUID

/** idempotent command의 tenant 및 operation boundary입니다. */
internal data class CommandScope(
    val tenantId: String,
    val operation: String,
) : Serializable {
    init {
        tenantId.requireNotBlank("tenantId")
        operation.requireNotBlank("operation")
        require(tenantId.length <= MAX_TENANT_LENGTH) { "tenantId is too long" }
        require(operation.length <= MAX_OPERATION_LENGTH) { "operation is too long" }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
        private const val MAX_TENANT_LENGTH = 64
        private const val MAX_OPERATION_LENGTH = 64
    }
}

/** replay window 동안 보존되는 닫힌 code-free response shape입니다. */
@ConsistentCopyVisibility
internal data class SafeResponseDescriptor private constructor(
    val status: Int,
    val outcome: String?,
    val effectId: UUID?,
    val revision: Long?,
    val terminalCode: VoucherPoolErrorCode?,
) : Serializable {
    init {
        require(status in MIN_HTTP_STATUS..MAX_HTTP_STATUS) { "descriptor status must be a valid HTTP status" }
        require(revision == null || revision >= 0) { "descriptor revision must be non-negative" }
        require(outcome == null || OUTCOME.matches(outcome)) { "descriptor outcome must be a bounded stable code" }
        if (terminalCode == null) {
            require(outcome != null && effectId != null && revision != null) {
                "success descriptor requires outcome, effect id, and revision"
            }
            require(status in MIN_SUCCESS_STATUS..MAX_SUCCESS_STATUS) {
                "success descriptor status must be 2xx or 3xx"
            }
        } else {
            require(outcome == null && effectId == null && revision == null) {
                "terminal descriptor must not retain success fields"
            }
            val semantics = VoucherPoolErrorCatalog[terminalCode]
            require(
                semantics.httpStatus == status &&
                    semantics.descriptorAction == DescriptorAction.STORE &&
                    semantics.tombstoneAction == TombstoneAction.STORE,
            ) { "terminal descriptor requires terminal stored error semantics" }
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
        private const val MIN_HTTP_STATUS = 100
        private const val MAX_HTTP_STATUS = 599
        private const val MIN_SUCCESS_STATUS = 200
        private const val MAX_SUCCESS_STATUS = 399
        private val OUTCOME = Regex("[A-Z][A-Z0-9_]{0,63}")

        fun success(status: Int, outcome: String, effectId: UUID, revision: Long): SafeResponseDescriptor =
            SafeResponseDescriptor(status, outcome, effectId, revision, null)

        fun terminal(status: Int, terminalCode: VoucherPoolErrorCode): SafeResponseDescriptor =
            SafeResponseDescriptor(status, null, null, null, terminalCode)

        internal fun restore(
            status: Int,
            outcome: String?,
            effectId: UUID?,
            revision: Long?,
            terminalCode: VoucherPoolErrorCode?,
        ): SafeResponseDescriptor = SafeResponseDescriptor(status, outcome, effectId, revision, terminalCode)
    }
}

/** full descriptor가 만료된 뒤 보존되는 minimal tenant-lifetime outcome입니다. */
@ConsistentCopyVisibility
internal data class EffectReference private constructor(
    val effectId: UUID?,
    val terminalCode: VoucherPoolErrorCode?,
) : Serializable {
    init {
        require((effectId == null) != (terminalCode == null)) { "effect reference requires exactly one terminal value" }
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        fun effect(effectId: UUID): EffectReference = EffectReference(effectId, null)

        fun terminal(code: VoucherPoolErrorCode): EffectReference = EffectReference(null, code)
    }
}

/** secret owner capability입니다. diagnostics와 persistence는 digest만 노출합니다. */
internal class IdempotencyOwner internal constructor(
    val scope: CommandScope,
    scopedKeyDigest: ByteArray,
    val keyVersion: Int,
    val fingerprint: CommandFingerprint,
    ownerTokenDigest: ByteArray,
) {
    private val scopedKeyDigest = scopedKeyDigest.copyOf()
    private val ownerTokenDigest = ownerTokenDigest.copyOf()

    init {
        require(keyVersion > 0) { "idempotency key version must be positive" }
        require(ownerTokenDigest.size == OWNER_DIGEST_BYTES) { "owner capability digest must contain 256 bits" }
    }

    fun copyScopedKeyDigest(): ByteArray = scopedKeyDigest.copyOf()

    internal fun copyTokenDigest(): ByteArray = ownerTokenDigest.copyOf()

    override fun equals(other: Any?): Boolean =
        other is IdempotencyOwner &&
            scope == other.scope &&
            MessageDigest.isEqual(scopedKeyDigest, other.scopedKeyDigest) &&
            MessageDigest.isEqual(copyTokenDigest(), other.copyTokenDigest())

    override fun hashCode(): Int = 31 * scope.hashCode() + scopedKeyDigest.contentHashCode()

    override fun toString(): String =
        "IdempotencyOwner(operation=${scope.operation}, scopedKeyDigest=[REDACTED], capability=[REDACTED])"
}

internal sealed interface IdempotencyDecision {
    data class Execute(val owner: IdempotencyOwner) : IdempotencyDecision, Serializable {
        companion object { private const val serialVersionUID: Long = 1L }
    }

    data class Replay(val descriptor: SafeResponseDescriptor) : IdempotencyDecision, Serializable {
        companion object { private const val serialVersionUID: Long = 1L }
    }

    data class Expired(
        val effectId: UUID?,
        val terminalCode: VoucherPoolErrorCode?,
    ) : IdempotencyDecision, Serializable {
        companion object { private const val serialVersionUID: Long = 1L }
    }

    data class InProgress(val retryAfter: Duration) : IdempotencyDecision, Serializable {
        companion object { private const val serialVersionUID: Long = 1L }
    }

    data object FingerprintConflict : IdempotencyDecision
}

internal interface VoucherPoolIdempotencyRepository {
    fun acquire(scope: CommandScope, rawKey: String, fingerprint: CommandFingerprint): IdempotencyDecision

    fun lockOwnerForExecution(owner: IdempotencyOwner)

    fun finalize(owner: IdempotencyOwner, descriptor: SafeResponseDescriptor, effect: EffectReference): Boolean

    fun releaseRetryable(owner: IdempotencyOwner): Boolean

    fun purgeDescriptors(limit: Int): Int
}

internal class IdempotencyOwnershipLostException :
    IllegalStateException("idempotency command ownership is no longer active")

internal class IdempotencyTombstoneKeyVersionException :
    IllegalStateException("idempotency tombstone key version is unsupported")

internal class IdempotencyDescriptorCorruptedException :
    IllegalStateException("stored idempotency descriptor is invalid")

internal class IdempotencyTombstoneIntegrityException :
    IllegalStateException("idempotency tombstone integrity check failed")

/**
 * owner lease, safe replay descriptor, tenant-lifetime command fence를 담당하는 PostgreSQL authority입니다.
 *
 * raw JDBC는 PostgreSQL lock과 JSONB operation을 caller의 기존 Exposed/Spring transaction 안에 유지합니다.
 * connection, commit, rollback ownership은 이 repository boundary를 넘지 않습니다.
 */
internal class JdbcVoucherPoolIdempotencyRepository(
    private val digests: VoucherDigestService,
    private val lease: Duration = Duration.ofSeconds(DEFAULT_LEASE_SECONDS),
    private val commandTimeout: Duration = Duration.ofMinutes(DEFAULT_COMMAND_TIMEOUT_MINUTES),
    private val descriptorRetention: Duration = Duration.ofHours(DEFAULT_DESCRIPTOR_RETENTION_HOURS),
    private val random: SecureRandom = SecureRandom(),
) : VoucherPoolIdempotencyRepository {
    init {
        requireDuration(lease, "lease")
        requireDuration(commandTimeout, "commandTimeout")
        requireDuration(descriptorRetention, "descriptorRetention")
        require(lease <= commandTimeout) { "lease must not exceed commandTimeout" }
    }

    override fun acquire(
        scope: CommandScope,
        rawKey: String,
        fingerprint: CommandFingerprint,
    ): IdempotencyDecision {
        require(rawKey.length in MIN_RAW_KEY_LENGTH..MAX_RAW_KEY_LENGTH) {
            "Idempotency-Key must contain $MIN_RAW_KEY_LENGTH..$MAX_RAW_KEY_LENGTH characters"
        }
        require(rawKey.none(Char::isISOControl)) { "Idempotency-Key must not contain control characters" }
        val connection = currentConnection()
        val keyDigest = digests.commandTombstone(scope.tenantId, scope.operation, rawKey)
        check(keyDigest.purpose == DigestPurpose.COMMAND_TOMBSTONE)
        lockRow(connection, scope, keyDigest.copyBytes())?.let { current ->
            return decideCurrent(connection, scope, keyDigest, fingerprint, current)
        }
        findTombstone(connection, scope, keyDigest.copyBytes())?.let { tombstone ->
            return tombstone.decision(fingerprint)
        }

        val owner = newOwner(scope, keyDigest, fingerprint)
        if (insertOwner(connection, owner)) {
            log.debug { "voucher_pool_idempotency_acquired operation=${scope.operation}" }
            return IdempotencyDecision.Execute(owner)
        }

        val current = lockRow(connection, scope, keyDigest.copyBytes())
            ?: findTombstone(connection, scope, keyDigest.copyBytes())?.let { return it.decision(fingerprint) }
            ?: error("idempotency row disappeared")
        return decideCurrent(connection, scope, keyDigest, fingerprint, current, owner)
    }

    private fun decideCurrent(
        connection: Connection,
        scope: CommandScope,
        keyDigest: VoucherDigest,
        fingerprint: CommandFingerprint,
        current: IdempotencyRow,
        proposedOwner: IdempotencyOwner? = null,
    ): IdempotencyDecision {
        if (!current.fingerprint.matches(fingerprint)) {
            log.warn { "voucher_pool_idempotency_fingerprint_conflict operation=${scope.operation}" }
            return IdempotencyDecision.FingerprintConflict
        }
        return when (current.status) {
            IdempotencyStatus.COMPLETED -> current.completedDecision(connection, scope, keyDigest.copyBytes())
            IdempotencyStatus.OWNED -> if (current.active) {
                IdempotencyDecision.InProgress(Duration.ofMillis(current.retryAfterMillis))
            } else {
                takeOver(connection, proposedOwner ?: newOwner(scope, keyDigest, fingerprint), current.revision)
            }
            IdempotencyStatus.RETRYABLE_FAILED ->
                takeOver(connection, proposedOwner ?: newOwner(scope, keyDigest, fingerprint), current.revision)
        }
    }

    override fun lockOwnerForExecution(owner: IdempotencyOwner) {
        val active = currentConnection().prepareStatement(
            """SELECT 1 FROM voucher_pool_http_idempotency
                WHERE tenant_id=? AND operation=? AND scoped_key_digest=? AND fingerprint=?
                  AND status='OWNED' AND owner_token_digest=?
                  AND lease_until>=statement_timestamp() AND command_deadline>=statement_timestamp()
                FOR UPDATE""",
        ).use { statement ->
            owner.bindScope(statement, start = 1)
            statement.setBytes(4, owner.fingerprint.copyBytes())
            statement.setBytes(5, owner.copyTokenDigest())
            statement.executeQuery().use(ResultSet::next)
        }
        if (!active) ownershipLost()
    }

    override fun finalize(
        owner: IdempotencyOwner,
        descriptor: SafeResponseDescriptor,
        effect: EffectReference,
    ): Boolean {
        requireDescriptorMatchesEffect(descriptor, effect)
        val connection = currentConnection()
        val updated = connection.prepareStatement(
            """UPDATE voucher_pool_http_idempotency
                SET status='COMPLETED',owner_token_digest=NULL,lease_until=NULL,descriptor=?::jsonb,
                    completed_at=statement_timestamp(),
                    expires_at=statement_timestamp()+(? * interval '1 millisecond'),revision=revision+1
                WHERE tenant_id=? AND operation=? AND scoped_key_digest=? AND fingerprint=?
                  AND status='OWNED' AND owner_token_digest=?
                  AND lease_until>=statement_timestamp() AND command_deadline>=statement_timestamp()""",
        ).use { statement ->
            statement.setString(1, descriptor.toJson())
            statement.setLong(2, descriptorRetention.toMillis())
            owner.bindScope(statement, start = 3)
            statement.setBytes(6, owner.fingerprint.copyBytes())
            statement.setBytes(7, owner.copyTokenDigest())
            statement.executeUpdate()
        }
        if (updated == 0) ownershipLost()

        insertOrVerifyTombstone(connection, owner, effect)
        log.debug { "voucher_pool_idempotency_finalized operation=${owner.scope.operation}" }
        return true
    }

    override fun releaseRetryable(owner: IdempotencyOwner): Boolean {
        val released = currentConnection().prepareStatement(
            """UPDATE voucher_pool_http_idempotency
                SET status='RETRYABLE_FAILED',owner_token_digest=NULL,lease_until=NULL,completed_at=NULL,
                    revision=revision+1
                WHERE tenant_id=? AND operation=? AND scoped_key_digest=? AND fingerprint=?
                  AND status='OWNED' AND owner_token_digest=?""",
        ).use { statement ->
            owner.bindScope(statement, start = 1)
            statement.setBytes(4, owner.fingerprint.copyBytes())
            statement.setBytes(5, owner.copyTokenDigest())
            statement.executeUpdate() == 1
        }
        log.debug { "voucher_pool_idempotency_retryable_release operation=${owner.scope.operation} released=$released" }
        return released
    }

    override fun purgeDescriptors(limit: Int): Int {
        require(limit in 1..MAX_PURGE_LIMIT) { "purge limit must be in 1..$MAX_PURGE_LIMIT" }
        val connection = currentConnection()
        val candidates = connection.prepareStatement(
            """SELECT i.tenant_id,i.operation,i.scoped_key_digest,i.fingerprint,
                       t.key_version,t.effect_id,t.terminal_code
                FROM voucher_pool_http_idempotency i
                JOIN voucher_pool_command_tombstones t
                  ON t.tenant_id=i.tenant_id AND t.operation=i.operation
                 AND t.scoped_key_digest=i.scoped_key_digest AND t.fingerprint=i.fingerprint
                WHERE i.status='COMPLETED' AND i.descriptor IS NOT NULL
                  AND i.expires_at<=statement_timestamp()
                ORDER BY i.expires_at,i.tenant_id,i.operation LIMIT ? FOR UPDATE OF i,t SKIP LOCKED""",
        ).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            PurgeCandidate(
                                tenantId = result.getString(1),
                                operation = result.getString(2),
                                scopedKeyDigest = result.getBytes(3),
                                fingerprint = result.getBytes(4),
                                tombstone = TombstoneRow(
                                    keyVersion = result.getInt(5),
                                    fingerprint = result.getBytes(4),
                                    effectId = result.getObject(6, UUID::class.java),
                                    terminalCode = result.getString(7)?.let(VoucherPoolErrorCode::valueOf),
                                ),
                            ),
                        )
                    }
                }
            }
        }
        var purged = 0
        candidates.forEach { candidate ->
            requireCurrentTombstoneVersion(candidate.tombstone)
            if (!MessageDigest.isEqual(candidate.tombstone.fingerprint, candidate.fingerprint)) {
                log.warn { "voucher_pool_idempotency_purge_fenced operation=${candidate.operation}" }
            } else {
                purged += clearDescriptor(connection, candidate)
            }
        }
        log.debug { "voucher_pool_idempotency_descriptors_purged count=$purged" }
        return purged
    }

    private fun insertOwner(connection: Connection, owner: IdempotencyOwner): Boolean =
        connection.prepareStatement(
            """INSERT INTO voucher_pool_http_idempotency
                (tenant_id,operation,scoped_key_digest,fingerprint,status,owner_token_digest,lease_until,
                 command_deadline,descriptor,expires_at,revision)
                VALUES (?,?,?,?,'OWNED',?,statement_timestamp()+(? * interval '1 millisecond'),
                    statement_timestamp()+(? * interval '1 millisecond'),NULL,
                    statement_timestamp()+(? * interval '1 millisecond'),0)
                ON CONFLICT DO NOTHING""",
        ).use { statement ->
            owner.bindScope(statement, start = 1)
            statement.setBytes(4, owner.fingerprint.copyBytes())
            statement.setBytes(5, owner.copyTokenDigest())
            statement.setLong(6, lease.toMillis())
            statement.setLong(7, commandTimeout.toMillis())
            statement.setLong(8, descriptorRetention.toMillis())
            statement.executeUpdate() == 1
        }

    private fun takeOver(connection: Connection, owner: IdempotencyOwner, expectedRevision: Long): IdempotencyDecision {
        val updated = connection.prepareStatement(
            """UPDATE voucher_pool_http_idempotency
                SET status='OWNED',owner_token_digest=?,lease_until=statement_timestamp()+(? * interval '1 millisecond'),
                    command_deadline=statement_timestamp()+(? * interval '1 millisecond'),descriptor=NULL,completed_at=NULL,
                    expires_at=statement_timestamp()+(? * interval '1 millisecond'),revision=revision+1
                WHERE tenant_id=? AND operation=? AND scoped_key_digest=? AND fingerprint=? AND revision=?
                  AND (status='RETRYABLE_FAILED' OR (status='OWNED' AND lease_until<=statement_timestamp()))""",
        ).use { statement ->
            statement.setBytes(1, owner.copyTokenDigest())
            statement.setLong(2, lease.toMillis())
            statement.setLong(3, commandTimeout.toMillis())
            statement.setLong(4, descriptorRetention.toMillis())
            owner.bindScope(statement, start = 5)
            statement.setBytes(8, owner.fingerprint.copyBytes())
            statement.setLong(9, expectedRevision)
            statement.executeUpdate()
        }
        check(updated == 1) { "locked idempotency owner takeover lost its revision" }
        log.warn { "voucher_pool_idempotency_owner_taken_over operation=${owner.scope.operation}" }
        return IdempotencyDecision.Execute(owner)
    }

    private fun lockRow(connection: Connection, scope: CommandScope, scopedKeyDigest: ByteArray): IdempotencyRow? =
        connection.prepareStatement(
            """SELECT fingerprint,status,descriptor,revision,
                    COALESCE(lease_until>statement_timestamp() AND command_deadline>statement_timestamp(),false) AS active,
                    GREATEST(0,COALESCE(EXTRACT(EPOCH FROM (lease_until-statement_timestamp()))*1000,0))::bigint AS retry_millis
                FROM voucher_pool_http_idempotency
                WHERE tenant_id=? AND operation=? AND scoped_key_digest=? FOR UPDATE""",
        ).use { statement ->
            statement.bindScope(scope, scopedKeyDigest)
            statement.executeQuery().use { result -> if (result.next()) result.idempotencyRow() else null }
        }

    private fun findTombstone(
        connection: Connection,
        scope: CommandScope,
        scopedKeyDigest: ByteArray,
    ): TombstoneRow? = connection.prepareStatement(
        """SELECT key_version,fingerprint,effect_id,terminal_code FROM voucher_pool_command_tombstones
            WHERE tenant_id=? AND operation=? AND scoped_key_digest=?""",
    ).use { statement ->
        statement.bindScope(scope, scopedKeyDigest)
        statement.executeQuery().use { result ->
            if (!result.next()) {
                null
            } else {
                TombstoneRow(
                    keyVersion = result.getInt("key_version"),
                    fingerprint = result.getBytes("fingerprint"),
                    effectId = result.getObject("effect_id", UUID::class.java),
                    terminalCode = result.getString("terminal_code")?.let(VoucherPoolErrorCode::valueOf),
                ).also(::requireCurrentTombstoneVersion)
            }
        }
    }

    private fun insertOrVerifyTombstone(connection: Connection, owner: IdempotencyOwner, effect: EffectReference) {
        connection.prepareStatement(
            """INSERT INTO voucher_pool_command_tombstones
                (tenant_id,operation,key_version,scoped_key_digest,fingerprint,effect_id,terminal_code)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING""",
        ).use { statement ->
            statement.setString(1, owner.scope.tenantId)
            statement.setString(2, owner.scope.operation)
            statement.setInt(3, owner.keyVersion)
            statement.setBytes(4, owner.copyScopedKeyDigest())
            statement.setBytes(5, owner.fingerprint.copyBytes())
            statement.setObject(6, effect.effectId)
            statement.setString(7, effect.terminalCode?.name)
            statement.executeUpdate()
        }
        val stored = checkNotNull(findTombstone(connection, owner.scope, owner.copyScopedKeyDigest()))
        if (!MessageDigest.isEqual(stored.fingerprint, owner.fingerprint.copyBytes())) {
            tombstoneIntegrityFailure()
        }
        if (stored.effectId != effect.effectId || stored.terminalCode != effect.terminalCode) {
            tombstoneIntegrityFailure()
        }
    }

    private fun clearDescriptor(connection: Connection, candidate: PurgeCandidate): Int =
        connection.prepareStatement(
            """UPDATE voucher_pool_http_idempotency SET descriptor=NULL,revision=revision+1
                WHERE tenant_id=? AND operation=? AND scoped_key_digest=? AND fingerprint=?
                  AND status='COMPLETED' AND descriptor IS NOT NULL AND expires_at<=statement_timestamp()""",
        ).use { statement ->
            statement.bindScope(candidate.scope, candidate.scopedKeyDigest)
            statement.setBytes(4, candidate.fingerprint)
            statement.executeUpdate()
        }

    private fun IdempotencyRow.completedDecision(
        connection: Connection,
        scope: CommandScope,
        scopedKeyDigest: ByteArray,
    ): IdempotencyDecision {
        val tombstone = findTombstone(connection, scope, scopedKeyDigest)
            ?: tombstoneIntegrityFailure()
        if (!MessageDigest.isEqual(tombstone.fingerprint, fingerprint.copyBytes())) {
            tombstoneIntegrityFailure()
        }
        return descriptor?.also {
            if (it.effectId != tombstone.effectId || it.terminalCode != tombstone.terminalCode) {
                tombstoneIntegrityFailure()
            }
        }?.let(IdempotencyDecision::Replay) ?: tombstone.decision(fingerprint)
    }

    private fun TombstoneRow.decision(fingerprint: CommandFingerprint): IdempotencyDecision =
        if (MessageDigest.isEqual(this.fingerprint, fingerprint.copyBytes())) {
            IdempotencyDecision.Expired(effectId, terminalCode)
        } else {
            IdempotencyDecision.FingerprintConflict
        }

    private fun CommandFingerprint.matches(other: CommandFingerprint): Boolean =
        MessageDigest.isEqual(copyBytes(), other.copyBytes())

    private fun newOwner(
        scope: CommandScope,
        keyDigest: VoucherDigest,
        fingerprint: CommandFingerprint,
    ): IdempotencyOwner {
        val tokenBytes = ByteArray(OWNER_TOKEN_BYTES).also(random::nextBytes)
        return try {
            IdempotencyOwner(
                scope = scope,
                scopedKeyDigest = keyDigest.copyBytes(),
                keyVersion = keyDigest.keyVersion,
                fingerprint = fingerprint,
                ownerTokenDigest = sha256(OWNER_TOKEN_DOMAIN, tokenBytes),
            )
        } finally {
            tokenBytes.fill(0)
        }
    }

    private fun currentConnection(): Connection =
        checkNotNull(TransactionManager.currentOrNull()) {
            "voucher idempotency repository requires an active VoucherPoolJdbcExecutor transaction"
        }.connection.connection as Connection

    private fun IdempotencyOwner.bindScope(statement: PreparedStatement, start: Int) {
        statement.setString(start, scope.tenantId)
        statement.setString(start + 1, scope.operation)
        statement.setBytes(start + 2, copyScopedKeyDigest())
    }

    private fun PreparedStatement.bindScope(scope: CommandScope, scopedKeyDigest: ByteArray) {
        setString(1, scope.tenantId)
        setString(2, scope.operation)
        setBytes(3, scopedKeyDigest)
    }

    private fun ResultSet.idempotencyRow(): IdempotencyRow = IdempotencyRow(
        fingerprint = CommandFingerprint.of(getBytes("fingerprint")),
        status = IdempotencyStatus.valueOf(getString("status")),
        descriptor = getString("descriptor")?.toDescriptor(),
        revision = getLong("revision"),
        active = getBoolean("active"),
        retryAfterMillis = getLong("retry_millis"),
    )

    private fun SafeResponseDescriptor.toJson(): String = jsonMapper { }.createObjectNode().apply {
        put("status", status)
        outcome?.let { put("outcome", it) }
        effectId?.let { put("effectId", it.toString()) }
        revision?.let { put("revision", it) }
        terminalCode?.let { put("terminalCode", it.name) }
    }.toString()

    private fun String.toDescriptor(): SafeResponseDescriptor {
        try {
            val node = jsonMapper { }.readTree(this)
            if (!node.isObject) descriptorCorrupted()
            val fieldNames: Set<String> = node.propertyNames().toSet()
            val statusNode = node.get("status")
            if (statusNode == null || !statusNode.isIntegralNumber || !statusNode.canConvertToInt()) {
                descriptorCorrupted()
            }
            return when (fieldNames) {
                SUCCESS_DESCRIPTOR_FIELDS -> {
                    val outcomeNode = node.get("outcome")
                    val effectIdNode = node.get("effectId")
                    val revisionNode = node.get("revision")
                    if (!outcomeNode.isString || !effectIdNode.isString) descriptorCorrupted()
                    if (!revisionNode.isIntegralNumber || !revisionNode.canConvertToLong()) descriptorCorrupted()
                    SafeResponseDescriptor.restore(
                        status = statusNode.intValue(),
                        outcome = outcomeNode.stringValue(),
                        effectId = UUID.fromString(effectIdNode.stringValue()),
                        revision = revisionNode.longValue(),
                        terminalCode = null,
                    )
                }
                TERMINAL_DESCRIPTOR_FIELDS -> {
                    val terminalCodeNode = node.get("terminalCode")
                    if (!terminalCodeNode.isString) descriptorCorrupted()
                    SafeResponseDescriptor.restore(
                        status = statusNode.intValue(),
                        outcome = null,
                        effectId = null,
                        revision = null,
                        terminalCode = VoucherPoolErrorCode.valueOf(terminalCodeNode.stringValue()),
                    )
                }
                else -> descriptorCorrupted()
            }
        } catch (_: Exception) {
            descriptorCorrupted()
        }
    }

    private fun requireDescriptorMatchesEffect(descriptor: SafeResponseDescriptor, effect: EffectReference) {
        require(descriptor.effectId == effect.effectId && descriptor.terminalCode == effect.terminalCode) {
            "safe descriptor and tenant-lifetime effect reference must match"
        }
    }

    private class IdempotencyRow(
        val fingerprint: CommandFingerprint,
        val status: IdempotencyStatus,
        val descriptor: SafeResponseDescriptor?,
        val revision: Long,
        val active: Boolean,
        val retryAfterMillis: Long,
    )

    private class TombstoneRow(
        val keyVersion: Int,
        val fingerprint: ByteArray,
        val effectId: UUID?,
        val terminalCode: VoucherPoolErrorCode?,
    )

    private class PurgeCandidate(
        val tenantId: String,
        val operation: String,
        val scopedKeyDigest: ByteArray,
        val fingerprint: ByteArray,
        val tombstone: TombstoneRow,
    ) {
        val scope: CommandScope get() = CommandScope(tenantId, operation)
    }

    private enum class IdempotencyStatus { OWNED, COMPLETED, RETRYABLE_FAILED }

    companion object : KLogging() {
        private const val DEFAULT_LEASE_SECONDS = 30L
        private const val DEFAULT_COMMAND_TIMEOUT_MINUTES = 1L
        private const val DEFAULT_DESCRIPTOR_RETENTION_HOURS = 24L
        private const val MIN_RAW_KEY_LENGTH = 8
        private const val MAX_RAW_KEY_LENGTH = 200
        private const val MAX_PURGE_LIMIT = 1_000
        private const val OWNER_TOKEN_BYTES = 32
        private val SUCCESS_DESCRIPTOR_FIELDS = setOf("status", "outcome", "effectId", "revision")
        private val TERMINAL_DESCRIPTOR_FIELDS = setOf("status", "terminalCode")
    }

    private fun requireCurrentTombstoneVersion(tombstone: TombstoneRow) {
        if (tombstone.keyVersion != digests.commandTombstoneKeyVersion) {
            throw IdempotencyTombstoneKeyVersionException()
        }
    }

    private fun ownershipLost(): Nothing = throw IdempotencyOwnershipLostException()

    private fun tombstoneIntegrityFailure(): Nothing = throw IdempotencyTombstoneIntegrityException()

    private fun descriptorCorrupted(): Nothing = throw IdempotencyDescriptorCorruptedException()
}

private fun requireDuration(duration: Duration, name: String) {
    require(!duration.isNegative && !duration.isZero) { "$name must be positive" }
    require(duration.toMillis() > 0) { "$name must be at least one millisecond" }
}

private fun sha256(domain: String, value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
    update(domain.toByteArray(UTF_8))
    update(0)
    digest(value)
}

private const val OWNER_TOKEN_DOMAIN = "voucher-pool-idempotency-owner-v1"
private const val OWNER_DIGEST_BYTES = 32
