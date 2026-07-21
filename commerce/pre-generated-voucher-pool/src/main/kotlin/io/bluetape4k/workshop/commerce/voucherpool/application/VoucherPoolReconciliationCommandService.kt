@file:Suppress("MagicNumber", "MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandScope
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.EffectReference
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyDecision
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyOwner
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.SafeResponseDescriptor
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.worker.JdbcVoucherPoolWorkerRepository
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerClaimSnapshot
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerKind
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.currentOrNull
import java.io.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

internal enum class ReconciliationCommandFailure {
    SCOPE_NOT_FOUND,
    COMMAND_IN_PROGRESS,
    IDEMPOTENCY_FINGERPRINT_CONFLICT,
    REPLAY_WINDOW_EXPIRED,
}

internal class ReconciliationCommandException(val reason: ReconciliationCommandFailure) : IllegalStateException(reason.name)

internal data class ReconciliationCommand(
    val tenantId: String,
    val batchId: UUID,
    val idempotencyKey: String,
) : Serializable {
    init {
        require(tenantId.isNotBlank() && tenantId.length <= MAX_TENANT_LENGTH)
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= MAX_IDEMPOTENCY_KEY_LENGTH)
    }

    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class ReconciliationProgressSnapshot(
    val kind: WorkerKind,
    val scopeId: UUID,
    val state: String,
    val cursor: Long,
    val checkpoint: Long,
    val attempt: Int,
    val nextAction: String,
    val revision: Long,
    val observedAt: Instant,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal interface VoucherPoolReconciliationCommandService {
    fun run(command: ReconciliationCommand): MutationResult<ReconciliationProgressSnapshot>
    fun progress(tenantId: String, batchId: UUID): ReconciliationProgressSnapshot?
}

/** Creates one durable batch-scoped reconciliation claim behind the shared idempotency fence. */
internal class JdbcVoucherPoolReconciliationCommandService(
    private val executor: VoucherPoolJdbcExecutor,
    private val repository: VoucherPoolRepository,
    private val idempotency: VoucherPoolIdempotencyRepository,
    private val claims: JdbcVoucherPoolWorkerRepository,
) : VoucherPoolReconciliationCommandService {
    override fun run(command: ReconciliationCommand): MutationResult<ReconciliationProgressSnapshot> {
        val decision = executor.operatorTransaction {
            idempotency.acquire(
                CommandScope(command.tenantId, OP_RECONCILIATION_RUN),
                command.idempotencyKey,
                VoucherPoolFingerprint.command(OP_RECONCILIATION_RUN, mapOf("batchId" to command.batchId.toString())),
            )
        }
        return when (decision) {
            is IdempotencyDecision.Execute -> executeOwned(decision.owner, command)
            is IdempotencyDecision.Replay -> MutationResult.Replay(decision.descriptor)
            is IdempotencyDecision.Expired -> MutationResult.Expired(decision.effectId, decision.terminalCode)
            is IdempotencyDecision.InProgress -> fail(ReconciliationCommandFailure.COMMAND_IN_PROGRESS)
            IdempotencyDecision.FingerprintConflict -> fail(ReconciliationCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT)
        }
    }

    override fun progress(tenantId: String, batchId: UUID): ReconciliationProgressSnapshot? =
        executor.operatorTransaction {
            selectProgress(currentConnection(), tenantId, batchId, forUpdate = false)
        }

    private fun executeOwned(
        owner: IdempotencyOwner,
        command: ReconciliationCommand,
    ): MutationResult<ReconciliationProgressSnapshot> = try {
        val progress = executor.operatorTransaction {
            val connection = currentConnection()
            idempotency.lockOwnerForExecution(owner)
            val campaignId = resolveCampaignId(connection, command.tenantId, command.batchId)
                ?: fail(ReconciliationCommandFailure.SCOPE_NOT_FOUND)
            repository.lockCampaignForShare(connection, command.tenantId, campaignId)
            repository.lockBatchForUpdate(connection, command.tenantId, command.batchId)
                ?: fail(ReconciliationCommandFailure.SCOPE_NOT_FOUND)
            val scheduled = claims.scheduleReconciliationInTransaction(connection, command.tenantId, command.batchId)
            if (!scheduled) fail(ReconciliationCommandFailure.COMMAND_IN_PROGRESS)
            val snapshot = checkNotNull(selectProgress(connection, command.tenantId, command.batchId, forUpdate = true))
            idempotency.finalize(
                owner,
                SafeResponseDescriptor.success(HTTP_ACCEPTED, "RECONCILIATION_ACCEPTED", command.batchId, snapshot.revision),
                EffectReference.effect(command.batchId),
            )
            snapshot
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
            // The command failure remains authoritative; the owner lease expires safely.
        }
    }

    private fun resolveCampaignId(connection: Connection, tenantId: String, batchId: UUID): UUID? =
        connection.prepareStatement(
            "SELECT campaign_id FROM voucher_pool_batches WHERE tenant_id=? AND batch_id=?",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, batchId)
            statement.executeQuery().use { result -> if (result.next()) result.getObject(1, UUID::class.java) else null }
        }

    private fun selectProgress(
        connection: Connection,
        tenantId: String,
        batchId: UUID,
        forUpdate: Boolean,
    ): ReconciliationProgressSnapshot? = connection.prepareStatement(
        "SELECT *,transaction_timestamp() AS observed_at FROM voucher_pool_worker_claims " +
            "WHERE tenant_id=? AND worker_type='RECONCILIATION' AND scope_id=?" + if (forUpdate) " FOR UPDATE" else "",
    ).use { statement ->
        statement.setString(1, tenantId)
        statement.setObject(2, batchId)
        statement.executeQuery().use { result -> if (result.next()) result.progressSnapshot() else null }
    }

    private fun ResultSet.progressSnapshot(): ReconciliationProgressSnapshot {
        val worker = WorkerClaimSnapshot(
            getString("tenant_id"),
            WorkerKind.valueOf(getString("worker_type")),
            getObject("scope_id", UUID::class.java),
            getString("owner_id"),
            getTimestamp("claim_until")?.toInstant(),
            getLong("cursor"),
            getInt("attempt"),
            getTimestamp("next_attempt_at").toInstant(),
            getLong("checkpoint"),
            getString("poison_reason"),
            getLong("revision"),
        )
        return ReconciliationProgressSnapshot(
            worker.kind,
            worker.scopeId,
            worker.state.name,
            worker.cursor,
            worker.checkpoint,
            worker.attempt,
            worker.nextAction,
            worker.revision,
            getTimestamp("observed_at").toInstant(),
        )
    }

    private fun currentConnection(): Connection = checkNotNull(TransactionManager.currentOrNull()) {
        "voucher pool reconciliation requires an active JDBC transaction"
    }.connection.connection as Connection
}

private fun fail(reason: ReconciliationCommandFailure): Nothing = throw ReconciliationCommandException(reason)

private const val HTTP_ACCEPTED = 202
private const val OP_RECONCILIATION_RUN = "reconciliation-run"
private const val MAX_TENANT_LENGTH = 64
private const val MAX_IDEMPOTENCY_KEY_LENGTH = 200
