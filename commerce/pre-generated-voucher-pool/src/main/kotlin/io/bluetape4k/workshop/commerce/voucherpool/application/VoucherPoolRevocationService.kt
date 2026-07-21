@file:Suppress("LongMethod", "LongParameterList", "MagicNumber", "MaxLineLength", "TooManyFunctions")

package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchPolicy
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignPolicy
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandScope
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.EffectReference
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyDecision
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyOwner
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.SafeResponseDescriptor
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigest
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.worker.JdbcVoucherPoolWorkerRepository
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerKind
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.currentOrNull
import java.io.Serializable
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal enum class RevokeAggregateType { CAMPAIGN, BATCH }

internal enum class RevocationCommandFailure {
    SCOPE_NOT_FOUND,
    STALE_REVISION,
    INVALID_STATE,
    INVALID_PREVIEW,
    COMMAND_IN_PROGRESS,
    IDEMPOTENCY_FINGERPRINT_CONFLICT,
    REPLAY_WINDOW_EXPIRED,
}

internal class RevocationCommandException(val reason: RevocationCommandFailure) : IllegalStateException(reason.name)

internal data class RevokePreviewSnapshot(
    val aggregateType: RevokeAggregateType,
    val aggregateId: UUID,
    val revision: Long,
    val counts: Map<EntryState, Long>,
    val eligibleDepth: Long,
    val activeReservations: Long,
    val activeAllocations: Long,
    val alreadyTerminalCount: Long,
    val affectedCount: Long,
    val previewToken: String,
    val expiresAt: Instant,
    val observedAt: Instant,
) : Serializable {
    override fun toString(): String =
        "RevokePreviewSnapshot(aggregateType=$aggregateType,aggregateId=$aggregateId,revision=$revision," +
            "counts=$counts,eligibleDepth=$eligibleDepth,activeReservations=$activeReservations," +
            "activeAllocations=$activeAllocations,alreadyTerminalCount=$alreadyTerminalCount," +
            "affectedCount=$affectedCount,previewToken=[REDACTED],expiresAt=$expiresAt,observedAt=$observedAt)"

    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class RevokeProgressSnapshot(
    val aggregateType: RevokeAggregateType,
    val aggregateId: UUID,
    val state: String,
    val revision: Long,
    val workerCount: Int,
    val observedAt: Instant,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal class RevokeCommand(
    val tenantId: String,
    val aggregateType: RevokeAggregateType,
    val aggregateId: UUID,
    val confirmedAggregateId: UUID,
    val expectedRevision: Long,
    val previewToken: String,
    val idempotencyKey: String,
) {
    init {
        require(tenantId.isNotBlank() && tenantId.length <= MAX_TENANT_LENGTH)
        require(expectedRevision >= 0)
        require(previewToken.isNotBlank() && previewToken.length <= MAX_PREVIEW_TOKEN_LENGTH)
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= MAX_IDEMPOTENCY_KEY_LENGTH)
    }

    override fun toString(): String =
        "RevokeCommand(tenantId=$tenantId,aggregateType=$aggregateType,aggregateId=$aggregateId," +
            "confirmedAggregateId=$confirmedAggregateId,expectedRevision=$expectedRevision," +
            "previewToken=[REDACTED],idempotencyKey=[REDACTED])"
}

internal interface VoucherPoolRevocationService {
    fun preview(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
        expectedRevision: Long,
    ): RevokePreviewSnapshot

    fun revoke(command: RevokeCommand): MutationResult<RevokeProgressSnapshot>

    fun progress(tenantId: String, aggregateType: RevokeAggregateType, aggregateId: UUID): RevokeProgressSnapshot?
}

/** Issues single-use signed preview grants and atomically hands accepted revocations to durable workers. */
internal class JdbcVoucherPoolRevocationService(
    private val executor: VoucherPoolJdbcExecutor,
    private val repository: VoucherPoolRepository,
    private val idempotency: VoucherPoolIdempotencyRepository,
    private val digests: VoucherDigestService,
    private val claims: JdbcVoucherPoolWorkerRepository,
    private val random: SecureRandom = SecureRandom(),
    private val previewLifetime: Duration = Duration.ofMinutes(5),
) : VoucherPoolRevocationService {
    init {
        require(!previewLifetime.isZero && !previewLifetime.isNegative)
    }

    override fun preview(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
        expectedRevision: Long,
    ): RevokePreviewSnapshot {
        require(tenantId.isNotBlank() && tenantId.length <= MAX_TENANT_LENGTH)
        require(expectedRevision >= 0)
        return executor.operatorTransaction {
            val connection = currentConnection()
            val authority = lockAggregate(connection, tenantId, aggregateType, aggregateId)
            requireRevision(authority.revision, expectedRevision)
            requireRevocable(aggregateType, authority.state)
            val impact = readImpact(connection, tenantId, aggregateType, aggregateId, authority.revision)
            issueGrant(connection, tenantId, impact)
        }
    }

    override fun revoke(command: RevokeCommand): MutationResult<RevokeProgressSnapshot> {
        val operation = command.aggregateType.operation()
        val scope = CommandScope(command.tenantId, operation)
        val decision = executor.operatorTransaction {
            idempotency.acquire(scope, command.idempotencyKey, command.fingerprint(operation))
        }
        return when (decision) {
            is IdempotencyDecision.Execute -> executeOwned(decision.owner, command)
            is IdempotencyDecision.Replay -> MutationResult.Replay(decision.descriptor)
            is IdempotencyDecision.Expired -> MutationResult.Expired(decision.effectId, decision.terminalCode)
            is IdempotencyDecision.InProgress -> fail(RevocationCommandFailure.COMMAND_IN_PROGRESS)
            IdempotencyDecision.FingerprintConflict -> fail(RevocationCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT)
        }
    }

    override fun progress(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
    ): RevokeProgressSnapshot? = executor.operatorTransaction {
        val connection = currentConnection()
        when (aggregateType) {
            RevokeAggregateType.CAMPAIGN -> connection.prepareStatement(
                "SELECT state,revision,transaction_timestamp() FROM voucher_pool_campaigns WHERE tenant_id=? AND campaign_id=?",
            ).use { statement ->
                statement.setString(1, tenantId)
                statement.setObject(2, aggregateId)
                statement.executeQuery().use { result ->
                    if (!result.next()) return@operatorTransaction null
                    RevokeProgressSnapshot(
                        aggregateType,
                        aggregateId,
                        result.getString(1),
                        result.getLong(2),
                        campaignWorkerCount(connection, tenantId, aggregateId),
                        result.getTimestamp(3).toInstant(),
                    )
                }
            }
            RevokeAggregateType.BATCH -> connection.prepareStatement(
                "SELECT state,revision,transaction_timestamp() FROM voucher_pool_batches WHERE tenant_id=? AND batch_id=?",
            ).use { statement ->
                statement.setString(1, tenantId)
                statement.setObject(2, aggregateId)
                statement.executeQuery().use { result ->
                    if (!result.next()) return@operatorTransaction null
                    RevokeProgressSnapshot(
                        aggregateType,
                        aggregateId,
                        result.getString(1),
                        result.getLong(2),
                        batchWorkerCount(connection, tenantId, aggregateId),
                        result.getTimestamp(3).toInstant(),
                    )
                }
            }
        }
    }

    private fun executeOwned(
        owner: IdempotencyOwner,
        command: RevokeCommand,
    ): MutationResult<RevokeProgressSnapshot> = try {
        val progress = executor.operatorTransaction {
            val connection = currentConnection()
            idempotency.lockOwnerForExecution(owner)
            val authority = lockAggregate(connection, command.tenantId, command.aggregateType, command.aggregateId)
            requireRevision(authority.revision, command.expectedRevision)
            if (command.confirmedAggregateId != command.aggregateId) fail(RevocationCommandFailure.INVALID_PREVIEW)
            val token = parseToken(command.previewToken)
            val grant = lockGrant(connection, command.tenantId, token.grantId)
                ?: fail(RevocationCommandFailure.INVALID_PREVIEW)
            val impact = readImpact(
                connection,
                command.tenantId,
                command.aggregateType,
                command.aggregateId,
                authority.revision,
            )
            validateGrant(command, token, grant, impact)
            requireRevocable(command.aggregateType, authority.state)
            consumeGrant(connection, grant)
            val updated = transitionAndHandoff(connection, command)
            idempotency.finalize(
                owner,
                SafeResponseDescriptor.success(HTTP_ACCEPTED, "REVOCATION_ACCEPTED", command.aggregateId, updated.revision),
                EffectReference.effect(command.aggregateId),
            )
            updated
        }
        MutationResult.Applied(progress)
    } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
        releaseOwner(owner)
        throw failure
    }

    private fun releaseOwner(owner: IdempotencyOwner) {
        try {
            executor.operatorTransaction { idempotency.releaseRetryable(owner) }
        } catch (_: RuntimeException) {
            // The original command failure remains authoritative; ownership expiry is a safe fallback.
        }
    }

    private fun transitionAndHandoff(
        connection: Connection,
        command: RevokeCommand,
    ): RevokeProgressSnapshot {
        val now = repository.transactionTime(connection)
        return when (command.aggregateType) {
            RevokeAggregateType.CAMPAIGN -> {
                val campaign = checkNotNull(repository.lockCampaignForUpdate(connection, command.tenantId, command.aggregateId))
                val updated = repository.updateCampaign(
                    connection,
                    campaign.copy(state = CampaignState.REVOKING),
                    campaign.revision,
                )
                val workerCount = campaignWorkerCount(connection, command.tenantId, command.aggregateId)
                val terminal = if (workerCount == 0) {
                    repository.updateCampaign(
                        connection,
                        updated.copy(state = CampaignState.REVOKED),
                        updated.revision,
                    )
                } else {
                    claims.ensureClaimInTransaction(
                        connection,
                        command.tenantId,
                        WorkerKind.CAMPAIGN_REVOKE,
                        command.aggregateId,
                    )
                    updated
                }
                RevokeProgressSnapshot(
                    command.aggregateType,
                    command.aggregateId,
                    terminal.state.name,
                    terminal.revision,
                    workerCount,
                    now,
                )
            }
            RevokeAggregateType.BATCH -> {
                val batch = checkNotNull(repository.lockBatchForUpdate(connection, command.tenantId, command.aggregateId))
                val updated = repository.updateBatchState(connection, batch, BatchState.REVOKING)
                claims.ensureClaimInTransaction(
                    connection,
                    command.tenantId,
                    WorkerKind.BATCH_REVOKE,
                    command.aggregateId,
                )
                RevokeProgressSnapshot(command.aggregateType, command.aggregateId, updated.state.name, updated.revision, 1, now)
            }
        }
    }

    private fun issueGrant(
        connection: Connection,
        tenantId: String,
        impact: ImpactSnapshot,
    ): RevokePreviewSnapshot {
        val grantId = UUID.randomUUID()
        val nonce = ByteArray(TOKEN_NONCE_BYTES).also(random::nextBytes)
        val expiresAt = impact.observedAt.plus(previewLifetime)
        val material = tokenMaterial(grantId, nonce, impact, expiresAt)
        val signature = digests.audit(tenantId, TOKEN_OPERATION, material)
        val signatureBytes = signature.copyBytes()
        return try {
            connection.prepareStatement(
                """INSERT INTO voucher_pool_revoke_preview_grants
                    (tenant_id,grant_id,aggregate_type,aggregate_id,aggregate_revision,impact_digest,affected_count,
                     signature_key_version,signature_digest,expires_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?)""",
            ).use { statement ->
                statement.setString(1, tenantId)
                statement.setObject(2, grantId)
                statement.setString(3, impact.aggregateType.name)
                statement.setObject(4, impact.aggregateId)
                statement.setLong(5, impact.revision)
                statement.setBytes(6, impact.impactDigest)
                statement.setLong(7, impact.affectedCount)
                statement.setInt(8, signature.keyVersion)
                statement.setBytes(9, signatureBytes)
                statement.setTimestamp(10, Timestamp.from(expiresAt))
                statement.executeUpdate()
            }
            val token = encodeToken(grantId, nonce, signature)
            RevokePreviewSnapshot(
                impact.aggregateType,
                impact.aggregateId,
                impact.revision,
                impact.counts,
                impact.eligibleDepth,
                impact.activeReservations,
                impact.activeAllocations,
                impact.alreadyTerminalCount,
                impact.affectedCount,
                token,
                expiresAt,
                impact.observedAt,
            )
        } finally {
            nonce.fill(0)
            signatureBytes.fill(0)
        }
    }

    private fun validateGrant(
        command: RevokeCommand,
        token: ParsedToken,
        grant: RevokeGrant,
        impact: ImpactSnapshot,
    ) {
        val now = impact.observedAt
        val validGrant = sequenceOf(
            grant.aggregateType == command.aggregateType,
            grant.aggregateId == command.aggregateId,
            grant.aggregateRevision == command.expectedRevision,
            grant.consumedAt == null,
            grant.expiresAt > now,
            grant.affectedCount == impact.affectedCount,
            MessageDigest.isEqual(grant.impactDigest, impact.impactDigest),
            grant.signatureKeyVersion == token.keyVersion,
            MessageDigest.isEqual(grant.signatureDigest, token.signature),
        ).all { it }
        if (!validGrant) {
            fail(RevocationCommandFailure.INVALID_PREVIEW)
        }
        val expected = VoucherDigest.of(DigestPurpose.AUDIT, token.keyVersion, token.signature)
        val material = tokenMaterial(token.grantId, token.nonce, impact, grant.expiresAt)
        if (!digests.matchesAudit(command.tenantId, TOKEN_OPERATION, material, expected)) {
            fail(RevocationCommandFailure.INVALID_PREVIEW)
        }
    }

    private fun consumeGrant(connection: Connection, grant: RevokeGrant) {
        connection.prepareStatement(
            """UPDATE voucher_pool_revoke_preview_grants SET consumed_at=transaction_timestamp()
                WHERE tenant_id=? AND grant_id=? AND consumed_at IS NULL AND expires_at>transaction_timestamp()""",
        ).use { statement ->
            statement.setString(1, grant.tenantId)
            statement.setObject(2, grant.grantId)
            if (statement.executeUpdate() != 1) fail(RevocationCommandFailure.INVALID_PREVIEW)
        }
    }

    private fun lockGrant(connection: Connection, tenantId: String, grantId: UUID): RevokeGrant? =
        connection.prepareStatement(
            "SELECT * FROM voucher_pool_revoke_preview_grants WHERE tenant_id=? AND grant_id=? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, grantId)
            statement.executeQuery().use { result -> if (result.next()) result.revokeGrant() else null }
        }

    private fun lockAggregate(
        connection: Connection,
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
    ): LockedAggregate = when (aggregateType) {
        RevokeAggregateType.CAMPAIGN -> {
            val campaign = repository.lockCampaignForUpdate(connection, tenantId, aggregateId)
                ?: fail(RevocationCommandFailure.SCOPE_NOT_FOUND)
            LockedAggregate(campaign.state.name, campaign.revision)
        }
        RevokeAggregateType.BATCH -> lockBatchAggregate(connection, tenantId, aggregateId)
    }

    private fun lockBatchAggregate(connection: Connection, tenantId: String, batchId: UUID): LockedAggregate {
        val campaignId = connection.prepareStatement(
            "SELECT campaign_id FROM voucher_pool_batches WHERE tenant_id=? AND batch_id=?",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, batchId)
            statement.executeQuery().use { result ->
                if (result.next()) result.getObject(1, UUID::class.java) else null
            }
        } ?: fail(RevocationCommandFailure.SCOPE_NOT_FOUND)
        repository.lockCampaignForShare(connection, tenantId, campaignId)
        val batch = repository.lockBatchForUpdate(connection, tenantId, batchId)
            ?: fail(RevocationCommandFailure.SCOPE_NOT_FOUND)
        return LockedAggregate(batch.state.name, batch.revision)
    }

    private fun readImpact(
        connection: Connection,
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
        revision: Long,
    ): ImpactSnapshot {
        val counts = EntryState.entries.associateWith { 0L }.toMutableMap()
        connection.prepareStatement(
            when (aggregateType) {
                RevokeAggregateType.CAMPAIGN ->
                    "SELECT state,count(*) FROM voucher_pool_entries WHERE tenant_id=? AND campaign_id=? GROUP BY state"
                RevokeAggregateType.BATCH ->
                    "SELECT state,count(*) FROM voucher_pool_entries WHERE tenant_id=? AND batch_id=? GROUP BY state"
            },
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, aggregateId)
            statement.executeQuery().use { result ->
                while (result.next()) counts[EntryState.valueOf(result.getString(1))] = result.getLong(2)
            }
        }
        val activeReservations = countScoped(
            connection,
            tenantId,
            aggregateType,
            aggregateId,
            "voucher_pool_reservations",
            "state='ACTIVE'",
        )
        val eligibleDepth = eligibleDepth(connection, tenantId, aggregateType, aggregateId)
        val activeAllocations = counts.getValue(EntryState.ALLOCATED)
        val affectedCount = counts.getValue(EntryState.AVAILABLE) +
            counts.getValue(EntryState.RESERVED) + activeAllocations
        val terminalCount = counts.getValue(EntryState.REDEEMED) + counts.getValue(EntryState.RELEASED) +
            counts.getValue(EntryState.REVOKED) + counts.getValue(EntryState.EXPIRED)
        val observedAt = repository.transactionTime(connection)
        val digest = impactDigest(
            aggregateType,
            aggregateId,
            revision,
            counts,
            eligibleDepth,
            activeReservations,
            activeAllocations,
            terminalCount,
            affectedCount,
        )
        return ImpactSnapshot(
            aggregateType,
            aggregateId,
            revision,
            counts.toMap(),
            eligibleDepth,
            activeReservations,
            activeAllocations,
            terminalCount,
            affectedCount,
            digest,
            observedAt,
        )
    }

    private fun eligibleDepth(
        connection: Connection,
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
    ): Long {
        val scope = if (aggregateType == RevokeAggregateType.CAMPAIGN) "e.campaign_id=?" else "e.batch_id=?"
        return connection.prepareStatement(
            """SELECT count(*) FROM voucher_pool_entries e
                JOIN voucher_pool_campaigns c
                  ON c.tenant_id=e.tenant_id AND c.campaign_id=e.campaign_id
                JOIN voucher_pool_batches b
                  ON b.tenant_id=e.tenant_id AND b.batch_id=e.batch_id
                WHERE e.tenant_id=? AND $scope AND e.state='AVAILABLE' AND e.quarantined_at IS NULL
                  AND c.state='ACTIVE' AND c.starts_at<=transaction_timestamp()
                  AND c.ends_at>transaction_timestamp() AND b.state='ACTIVE'
                  AND b.activates_at<=transaction_timestamp()
                  AND (b.expires_at IS NULL OR b.expires_at>transaction_timestamp())""",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, aggregateId)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }
    }

    private fun countScoped(
        connection: Connection,
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
        table: String,
        predicate: String,
    ): Long = connection.prepareStatement(
        "SELECT count(*) FROM $table WHERE tenant_id=? AND " +
            if (aggregateType == RevokeAggregateType.CAMPAIGN) "campaign_id=? AND $predicate" else "batch_id=? AND $predicate",
    ).use { statement ->
        statement.setString(1, tenantId)
        statement.setObject(2, aggregateId)
        statement.executeQuery().use { result -> result.next(); result.getLong(1) }
    }

    private fun campaignWorkerCount(connection: Connection, tenantId: String, campaignId: UUID): Int =
        connection.prepareStatement(
            """SELECT count(*) FROM voucher_pool_batches
                WHERE tenant_id=? AND campaign_id=? AND state NOT IN ('REVOKED','EXPIRED')""",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, campaignId)
            statement.executeQuery().use { result -> result.next(); result.getInt(1) }
        }

    private fun batchWorkerCount(connection: Connection, tenantId: String, batchId: UUID): Int =
        connection.prepareStatement(
            "SELECT count(*) FROM voucher_pool_worker_claims WHERE tenant_id=? AND worker_type='BATCH_REVOKE' AND scope_id=?",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, batchId)
            statement.executeQuery().use { result -> result.next(); result.getInt(1) }
        }

    private fun requireRevision(actual: Long, expected: Long) {
        if (actual != expected) fail(RevocationCommandFailure.STALE_REVISION)
    }

    private fun requireRevocable(aggregateType: RevokeAggregateType, state: String) {
        val allowed = when (aggregateType) {
            RevokeAggregateType.CAMPAIGN -> CampaignPolicy.canTransition(CampaignState.valueOf(state), CampaignState.REVOKING)
            RevokeAggregateType.BATCH -> BatchPolicy.canTransition(BatchState.valueOf(state), BatchState.REVOKING)
        }
        if (!allowed) fail(RevocationCommandFailure.INVALID_STATE)
    }

    private fun RevokeCommand.fingerprint(operation: String) = VoucherPoolFingerprint.command(
        operation,
        mapOf(
            "aggregateType" to aggregateType.name,
            "aggregateId" to aggregateId.toString(),
            "confirmedAggregateId" to confirmedAggregateId.toString(),
            "expectedRevision" to expectedRevision.toString(),
            "previewTokenDigest" to MessageDigest.getInstance("SHA-256").digest(previewToken.toByteArray(UTF_8)).toHex(),
        ),
    )

    private fun RevokeAggregateType.operation(): String = "${name.lowercase()}-revoke"

    private fun tokenMaterial(
        grantId: UUID,
        nonce: ByteArray,
        impact: ImpactSnapshot,
        expiresAt: Instant,
    ): String = listOf(
        grantId,
        Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
        impact.aggregateType,
        impact.aggregateId,
        impact.revision,
        Base64.getUrlEncoder().withoutPadding().encodeToString(impact.impactDigest),
        impact.affectedCount,
        expiresAt.epochSecond,
        expiresAt.nano,
    ).joinToString("|")

    private fun encodeToken(grantId: UUID, nonce: ByteArray, signature: VoucherDigest): String {
        val signatureBytes = signature.copyBytes()
        return try {
            listOf(
                TOKEN_VERSION,
                grantId,
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
                signature.keyVersion,
                Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes),
            ).joinToString(".")
        } finally {
            signatureBytes.fill(0)
        }
    }

    private fun parseToken(raw: String): ParsedToken = try {
        val parts = raw.split('.')
        if (parts.size != TOKEN_PARTS || parts[0] != TOKEN_VERSION) fail(RevocationCommandFailure.INVALID_PREVIEW)
        val nonce = Base64.getUrlDecoder().decode(parts[2])
        val signature = Base64.getUrlDecoder().decode(parts[4])
        if (nonce.size != TOKEN_NONCE_BYTES || signature.size != TOKEN_SIGNATURE_BYTES) {
            fail(RevocationCommandFailure.INVALID_PREVIEW)
        }
        ParsedToken(UUID.fromString(parts[1]), nonce, parts[3].toInt(), signature)
    } catch (failure: RevocationCommandException) {
        throw failure
    } catch (_: RuntimeException) {
        fail(RevocationCommandFailure.INVALID_PREVIEW)
    }

    private fun currentConnection(): Connection = checkNotNull(TransactionManager.currentOrNull()) {
        "voucher pool revocation requires an active JDBC transaction"
    }.connection.connection as Connection

    private data class LockedAggregate(val state: String, val revision: Long) : Serializable {
        companion object { private const val serialVersionUID: Long = 1L }
    }

    private data class ImpactSnapshot(
        val aggregateType: RevokeAggregateType,
        val aggregateId: UUID,
        val revision: Long,
        val counts: Map<EntryState, Long>,
        val eligibleDepth: Long,
        val activeReservations: Long,
        val activeAllocations: Long,
        val alreadyTerminalCount: Long,
        val affectedCount: Long,
        val impactDigest: ByteArray,
        val observedAt: Instant,
    ) : Serializable {
        companion object { private const val serialVersionUID: Long = 1L }
    }

    private data class ParsedToken(
        val grantId: UUID,
        val nonce: ByteArray,
        val keyVersion: Int,
        val signature: ByteArray,
    ) : Serializable {
        companion object { private const val serialVersionUID: Long = 1L }
    }

    private data class RevokeGrant(
        val tenantId: String,
        val grantId: UUID,
        val aggregateType: RevokeAggregateType,
        val aggregateId: UUID,
        val aggregateRevision: Long,
        val impactDigest: ByteArray,
        val affectedCount: Long,
        val signatureKeyVersion: Int,
        val signatureDigest: ByteArray,
        val expiresAt: Instant,
        val consumedAt: Instant?,
    ) : Serializable {
        companion object { private const val serialVersionUID: Long = 1L }
    }

    private fun ResultSet.revokeGrant() = RevokeGrant(
        getString("tenant_id"),
        getObject("grant_id", UUID::class.java),
        RevokeAggregateType.valueOf(getString("aggregate_type")),
        getObject("aggregate_id", UUID::class.java),
        getLong("aggregate_revision"),
        getBytes("impact_digest"),
        getLong("affected_count"),
        getInt("signature_key_version"),
        getBytes("signature_digest"),
        getTimestamp("expires_at").toInstant(),
        getTimestamp("consumed_at")?.toInstant(),
    )
}

private fun impactDigest(
    aggregateType: RevokeAggregateType,
    aggregateId: UUID,
    revision: Long,
    counts: Map<EntryState, Long>,
    eligibleDepth: Long,
    activeReservations: Long,
    activeAllocations: Long,
    terminalCount: Long,
    affectedCount: Long,
): ByteArray {
    val material = buildString {
        append(aggregateType).append('|').append(aggregateId).append('|').append(revision)
        EntryState.entries.forEach { state -> append('|').append(state).append('=').append(counts[state] ?: 0L) }
        append('|').append(eligibleDepth)
        append('|').append(activeReservations)
        append('|').append(activeAllocations)
        append('|').append(terminalCount)
        append('|').append(affectedCount)
    }
    return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(UTF_8))
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

private fun fail(reason: RevocationCommandFailure): Nothing = throw RevocationCommandException(reason)

private const val HTTP_ACCEPTED = 202
private const val MAX_TENANT_LENGTH = 64
private const val MAX_PREVIEW_TOKEN_LENGTH = 512
private const val MAX_IDEMPOTENCY_KEY_LENGTH = 200
private const val TOKEN_OPERATION = "operator-revoke-preview-token-v1"
private const val TOKEN_VERSION = "v1"
private const val TOKEN_PARTS = 5
private const val TOKEN_NONCE_BYTES = 24
private const val TOKEN_SIGNATURE_BYTES = 32
