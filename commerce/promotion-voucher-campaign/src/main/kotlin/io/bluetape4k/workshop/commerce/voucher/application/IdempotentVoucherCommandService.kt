package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.idempotency.Digest
import io.bluetape4k.workshop.commerce.voucher.idempotency.HttpIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucher.idempotency.IdempotencyAcquireResult
import io.bluetape4k.workshop.commerce.voucher.idempotency.IdempotencyScope
import io.bluetape4k.workshop.commerce.voucher.idempotency.OwnerToken
import io.bluetape4k.workshop.commerce.voucher.idempotency.StoredHttpResponse
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherIdempotencyStore
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherResponseKind
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherTransactionRunner
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal data class IdempotentVoucherCommand(
    val scope: IdempotencyScope,
    val fingerprint: Digest,
    val lease: Duration = HttpIdempotencyRepository.DEFAULT_LEASE,
    val commandTimeout: Duration = HttpIdempotencyRepository.DEFAULT_COMMAND_TIMEOUT,
    val retention: Duration = HttpIdempotencyRepository.DEFAULT_RETENTION,
)

internal sealed interface IdempotentCommandResult {
    data class Completed(
        val response: StoredHttpResponse,
        val replayed: Boolean,
    ) : IdempotentCommandResult

    data class Retryable(
        val response: StoredHttpResponse,
    ) : IdempotentCommandResult

    data class InProgress(
        val retryAfter: Duration,
    ) : IdempotentCommandResult

    data object FingerprintConflict : IdempotentCommandResult
}

internal enum class IdempotencyCutPoint {
    AFTER_ACQUIRE,
    BEFORE_BUSINESS,
    AFTER_FINALIZE_BEFORE_COMMIT,
    AFTER_COMMIT_BEFORE_RESPONSE,
}

/** Signals a retryable command outcome so the enclosing business transaction rolls back. */
internal class RetryableVoucherCommand(
    val response: StoredHttpResponse,
) : RuntimeException(response.responseKind.name) {
    init {
        require(response.responseKind in RETRYABLE_KINDS) { "response kind is not retryable with the same key" }
    }

    companion object {
        private val RETRYABLE_KINDS =
            setOf(
                VoucherResponseKind.RATE_LIMITED,
                VoucherResponseKind.DATABASE_BULKHEAD_REJECTED,
                VoucherResponseKind.AUTHORITATIVE_BACKEND_UNAVAILABLE,
                VoucherResponseKind.CAMPAIGN_PAUSED,
                VoucherResponseKind.IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE,
                VoucherResponseKind.RECONCILIATION_IN_PROGRESS,
            )
    }
}

/**
 * Separates replay, admission, acquisition, and business transactions so virtual threads never
 * wait on Redis/risk work while retaining a JDBC permit or connection.
 */
@Service
internal class IdempotentVoucherCommandService(
    private val idempotency: VoucherIdempotencyStore,
    private val transactions: VoucherTransactionRunner,
    private val clock: Clock,
    private val ownerTokens: () -> OwnerToken = OwnerToken::random,
    private val cutPoint: (IdempotencyCutPoint) -> Unit = {},
) {
    fun execute(
        command: IdempotentVoucherCommand,
        admission: () -> StoredHttpResponse?,
        business: () -> StoredHttpResponse,
    ): IdempotentCommandResult = executeOwned(command, admission, transactionalBusiness = true, business)

    /**
     * Runs a command whose business operation owns smaller independent transactions, such as a
     * bounded reconciliation batch. Owner validation and terminal replay persistence remain
     * PostgreSQL-authoritative, but no foreground permit is retained across the external work.
     */
    fun executeExternal(
        command: IdempotentVoucherCommand,
        admission: () -> StoredHttpResponse?,
        business: () -> StoredHttpResponse,
    ): IdempotentCommandResult = executeOwned(command, admission, transactionalBusiness = false, business)

    private fun executeOwned(
        command: IdempotentVoucherCommand,
        admission: () -> StoredHttpResponse?,
        transactionalBusiness: Boolean,
        business: () -> StoredHttpResponse,
    ): IdempotentCommandResult {
        val replay =
            transactions.foregroundTransaction {
                idempotency.lookup(command.scope, command.fingerprint)
            }
        terminalResult(replay)?.let { return it }

        admission()?.let { rejected ->
            require(rejected.responseKind in RETRYABLE_WITHOUT_OWNER) {
                "admission may return only retryable non-terminal outcomes"
            }
            return IdempotentCommandResult.Retryable(rejected)
        }

        val now = Instant.now(clock)
        val acquired =
            transactions.foregroundTransaction {
                idempotency.acquire(
                    scope = command.scope,
                    fingerprint = command.fingerprint,
                    now = now,
                    ownerToken = ownerTokens(),
                    lease = command.lease,
                    commandTimeout = command.commandTimeout,
                    retention = command.retention,
                )
            }
        terminalResult(acquired)?.let { return it }
        if (acquired is IdempotencyAcquireResult.InProgress) {
            return IdempotentCommandResult.InProgress(acquired.retryAfter.coerceAtMost(command.lease))
        }
        check(acquired is IdempotencyAcquireResult.Owner) { "unexpected idempotency acquisition result" }
        cutPoint(IdempotencyCutPoint.AFTER_ACQUIRE)

        val response =
            try {
                if (transactionalBusiness) {
                    transactions.foregroundTransaction {
                        requireOwner(command, acquired, Instant.now(clock))
                        cutPoint(IdempotencyCutPoint.BEFORE_BUSINESS)
                        val completed = business()
                        finalize(command, acquired, completed)
                        completed
                    }
                } else {
                    transactions.foregroundTransaction {
                        requireOwner(command, acquired, Instant.now(clock))
                    }
                    cutPoint(IdempotencyCutPoint.BEFORE_BUSINESS)
                    val completed = business()
                    transactions.foregroundTransaction {
                        finalize(command, acquired, completed)
                    }
                    completed
                }
            } catch (retryable: RetryableVoucherCommand) {
                transactions.foregroundTransaction {
                    idempotency.release(command.scope, acquired.ownerToken)
                }
                log.warn {
                    "voucher_command_retryable operation=${command.scope.operation} " +
                        "kind=${retryable.response.responseKind} keyDigestPrefix=${command.scope.keyDigest.base64Url.take(12)}"
                }
                return IdempotentCommandResult.Retryable(retryable.response)
            }

        cutPoint(IdempotencyCutPoint.AFTER_COMMIT_BEFORE_RESPONSE)
        log.debug {
            "voucher_command_completed operation=${command.scope.operation} responseKind=${response.responseKind} " +
                "keyDigestPrefix=${command.scope.keyDigest.base64Url.take(12)}"
        }
        return IdempotentCommandResult.Completed(response, replayed = false)
    }

    private fun requireOwner(
        command: IdempotentVoucherCommand,
        acquired: IdempotencyAcquireResult.Owner,
        now: Instant,
    ) {
        check(idempotency.isOwner(command.scope, acquired.ownerToken, now)) {
            "idempotency owner or command deadline was lost before business mutation"
        }
    }

    private fun finalize(
        command: IdempotentVoucherCommand,
        acquired: IdempotencyAcquireResult.Owner,
        response: StoredHttpResponse,
    ) {
        check(idempotency.finalize(command.scope, acquired.ownerToken, Instant.now(clock), response)) {
            "idempotency owner was lost before terminal finalize"
        }
        cutPoint(IdempotencyCutPoint.AFTER_FINALIZE_BEFORE_COMMIT)
    }

    private fun terminalResult(result: IdempotencyAcquireResult?): IdempotentCommandResult? =
        when (result) {
            is IdempotencyAcquireResult.Replay ->
                IdempotentCommandResult.Completed(result.response, replayed = true)
            IdempotencyAcquireResult.FingerprintConflict -> IdempotentCommandResult.FingerprintConflict
            else -> null
        }

    companion object : KLogging() {
        private val RETRYABLE_WITHOUT_OWNER =
            setOf(
                VoucherResponseKind.RATE_LIMITED,
                VoucherResponseKind.DATABASE_BULKHEAD_REJECTED,
                VoucherResponseKind.AUTHORITATIVE_BACKEND_UNAVAILABLE,
                VoucherResponseKind.CAMPAIGN_PAUSED,
                VoucherResponseKind.IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE,
                VoucherResponseKind.RECONCILIATION_IN_PROGRESS,
            )
    }
}
