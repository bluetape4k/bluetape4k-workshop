package io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.IdempotencyReceipts
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireLt
import java.time.Duration
import java.time.Instant

private const val FIRST_ERROR_STATUS = 400
private const val DEFAULT_RECEIPT_LEASE_SECONDS = 90L
private const val DEFAULT_RECEIPT_COMMAND_TIMEOUT_SECONDS = 60L
private val DEFAULT_RECEIPT_LEASE: Duration = Duration.ofSeconds(DEFAULT_RECEIPT_LEASE_SECONDS)
private val DEFAULT_RECEIPT_COMMAND_TIMEOUT: Duration = Duration.ofSeconds(DEFAULT_RECEIPT_COMMAND_TIMEOUT_SECONDS)

private data class ReceiptAcquireRequest(
    val scope: ReceiptScope,
    val fingerprint: ReceiptDigest,
    val now: Instant,
    val ownerToken: ReceiptOwnerToken = ReceiptOwnerToken.random(),
    val lease: Duration = DEFAULT_RECEIPT_LEASE,
    val commandTimeout: Duration = DEFAULT_RECEIPT_COMMAND_TIMEOUT,
)

/**
 * PostgreSQL receipt authority. Every mutating call requires the active foreground transaction;
 * command orchestration owns the short acquire transaction and append/finalize transaction boundary.
 */
internal class EventSourcedIdempotencyRepository {

    fun acquire(
        scope: ReceiptScope,
        fingerprint: ReceiptDigest,
        now: Instant,
    ): ReceiptAcquireResult = acquire(ReceiptAcquireRequest(scope, fingerprint, now))

    private fun acquire(request: ReceiptAcquireRequest): ReceiptAcquireResult =
        with(request) {
            TransactionManager.current()
            validateTiming(lease, commandTimeout)
            val leaseDeadline = now.plus(lease)
            val requestFingerprint = fingerprint.value
            val inserted =
                IdempotencyReceipts.insertIgnore { row ->
                    row[IdempotencyReceipts.tenantId] = scope.tenantId.value
                    row[IdempotencyReceipts.principalDigest] = scope.principalDigest.value
                    row[IdempotencyReceipts.operation] = scope.operation
                    row[IdempotencyReceipts.resourceId] = scope.resourceId
                    row[IdempotencyReceipts.keyDigest] = scope.keyDigest.value
                    row[IdempotencyReceipts.fingerprint] = requestFingerprint
                    row[IdempotencyReceipts.status] = ReceiptStatus.IN_PROGRESS
                    row[IdempotencyReceipts.ownerTokenDigest] = ownerToken.digest().value
                    row[IdempotencyReceipts.leaseDeadline] = leaseDeadline
                    row[IdempotencyReceipts.commandDeadline] = now.plus(commandTimeout)
                    row[IdempotencyReceipts.createdAt] = now
                    row[IdempotencyReceipts.updatedAt] = now
                }.insertedCount == 1
            if (inserted) {
                log.debug { "voucher_receipt_acquired operation=${scope.operation}" }
                ReceiptAcquireResult.Owner(ownerToken, leaseDeadline)
            } else {
                acquireExisting(request, leaseDeadline)
            }
        }

    private fun acquireExisting(
        request: ReceiptAcquireRequest,
        leaseDeadline: Instant,
    ): ReceiptAcquireResult {
        val scope = request.scope
        val fingerprint = request.fingerprint
        val now = request.now
        val ownerToken = request.ownerToken
        val current = checkNotNull(find(scope)) { "idempotency receipt disappeared after duplicate acquire" }
        val currentLease = current.leaseDeadline
        return when {
            current.fingerprint != fingerprint.value -> {
                log.warn { "voucher_receipt_fingerprint_conflict operation=${scope.operation}" }
                ReceiptAcquireResult.FingerprintConflict
            }
            current.descriptor != null -> ReceiptAcquireResult.Replay(current.descriptor)
            checkNotNull(currentLease) { "in-progress receipt must have a lease" }.isAfter(now) ->
                ReceiptAcquireResult.InProgress(Duration.between(now, currentLease))
            reclaimExpiredLease(request, current, leaseDeadline) -> {
                log.warn { "voucher_receipt_lease_taken_over operation=${scope.operation}" }
                ReceiptAcquireResult.Owner(ownerToken, leaseDeadline)
            }
            else -> retryAcquire(scope, now)
        }
    }

    private fun reclaimExpiredLease(
        request: ReceiptAcquireRequest,
        current: StoredReceipt,
        leaseDeadline: Instant,
    ): Boolean {
        val scope = request.scope
        val now = request.now
        val ownerToken = request.ownerToken
        val commandTimeout = request.commandTimeout
        val currentLease = checkNotNull(current.leaseDeadline) { "in-progress receipt must have a lease" }
        return IdempotencyReceipts.update(
                where = {
                    scopePredicate(scope) and
                        (IdempotencyReceipts.status eq ReceiptStatus.IN_PROGRESS) and
                        (IdempotencyReceipts.ownerTokenDigest eq current.ownerTokenDigest) and
                        (IdempotencyReceipts.leaseDeadline eq currentLease)
                },
            ) { row ->
                row[IdempotencyReceipts.ownerTokenDigest] = ownerToken.digest().value
                row[IdempotencyReceipts.leaseDeadline] = leaseDeadline
                row[IdempotencyReceipts.commandDeadline] = now.plus(commandTimeout)
                row[IdempotencyReceipts.updatedAt] = now
            } == 1
    }

    private fun retryAcquire(
        scope: ReceiptScope,
        now: Instant,
    ): ReceiptAcquireResult {
        val retried = checkNotNull(find(scope)) { "idempotency receipt disappeared after lease takeover race" }
        return retried.descriptor?.let(ReceiptAcquireResult::Replay)
            ?: ReceiptAcquireResult.InProgress(
                Duration.between(now, checkNotNull(retried.leaseDeadline)).coerceAtLeast(Duration.ZERO),
            )
    }

    fun isOwner(
        scope: ReceiptScope,
        fingerprint: ReceiptDigest,
        ownerToken: ReceiptOwnerToken,
        now: Instant,
    ): Boolean {
        TransactionManager.current()
        return IdempotencyReceipts
            .selectAll()
            .where {
                scopePredicate(scope) and
                    (IdempotencyReceipts.fingerprint eq fingerprint.value) and
                    (IdempotencyReceipts.status eq ReceiptStatus.IN_PROGRESS) and
                    (IdempotencyReceipts.ownerTokenDigest eq ownerToken.digest().value) and
                    (IdempotencyReceipts.leaseDeadline greaterEq now) and
                    (IdempotencyReceipts.commandDeadline greaterEq now)
            }.count() == 1L
    }

    fun finalize(
        scope: ReceiptScope,
        fingerprint: ReceiptDigest,
        ownerToken: ReceiptOwnerToken,
        now: Instant,
        descriptor: TerminalDescriptor,
    ): Boolean {
        TransactionManager.current()
        val finalized =
            IdempotencyReceipts.update(
                where = {
                    scopePredicate(scope) and
                        (IdempotencyReceipts.fingerprint eq fingerprint.value) and
                        (IdempotencyReceipts.status eq ReceiptStatus.IN_PROGRESS) and
                        (IdempotencyReceipts.ownerTokenDigest eq ownerToken.digest().value) and
                        (IdempotencyReceipts.leaseDeadline greaterEq now) and
                        (IdempotencyReceipts.commandDeadline greaterEq now)
                },
            ) { row ->
                row[IdempotencyReceipts.status] =
                    if (descriptor.status < FIRST_ERROR_STATUS) ReceiptStatus.SUCCEEDED else ReceiptStatus.FAILED
                row[IdempotencyReceipts.terminalOutcome] = descriptor.outcome
                row[IdempotencyReceipts.terminalStatus] = descriptor.status
                row[IdempotencyReceipts.allocationId] = descriptor.allocationId
                row[IdempotencyReceipts.generationKeyVersion] = descriptor.generationKeyVersion
                row[IdempotencyReceipts.verificationKeyVersion] = descriptor.verificationKeyVersion
                row[IdempotencyReceipts.terminalObservedAt] = descriptor.observedAt
                row[IdempotencyReceipts.terminalStreamPosition] = descriptor.streamPosition
                row[IdempotencyReceipts.ownerTokenDigest] = null
                row[IdempotencyReceipts.leaseDeadline] = null
                row[IdempotencyReceipts.updatedAt] = now
            } == 1
        log.debug { "voucher_receipt_finalized operation=${scope.operation} finalized=$finalized" }
        return finalized
    }

    companion object : KLogging()
}

private data class StoredReceipt(
    val fingerprint: String,
    val ownerTokenDigest: String?,
    val leaseDeadline: Instant?,
    val descriptor: TerminalDescriptor?,
)

private fun find(scope: ReceiptScope): StoredReceipt? =
    IdempotencyReceipts
        .selectAll()
        .where { scopePredicate(scope) }
        .singleOrNull()
        ?.let(::toStoredReceipt)

private fun scopePredicate(scope: ReceiptScope) =
    (IdempotencyReceipts.tenantId eq scope.tenantId.value) and
        (IdempotencyReceipts.principalDigest eq scope.principalDigest.value) and
        (IdempotencyReceipts.operation eq scope.operation) and
        (IdempotencyReceipts.resourceId eq scope.resourceId) and
        (IdempotencyReceipts.keyDigest eq scope.keyDigest.value)

private fun toStoredReceipt(row: ResultRow): StoredReceipt {
    val status = row[IdempotencyReceipts.status]
    val descriptor =
        if (status == ReceiptStatus.IN_PROGRESS) {
            null
        } else {
            TerminalDescriptor(
                outcome = checkNotNull(row[IdempotencyReceipts.terminalOutcome]),
                status = checkNotNull(row[IdempotencyReceipts.terminalStatus]),
                allocationId = row[IdempotencyReceipts.allocationId],
                generationKeyVersion = row[IdempotencyReceipts.generationKeyVersion],
                verificationKeyVersion = row[IdempotencyReceipts.verificationKeyVersion],
            ).let { descriptor ->
                row[IdempotencyReceipts.terminalObservedAt]?.let(descriptor::withObservedAt) ?: descriptor
            }.let { descriptor ->
                row[IdempotencyReceipts.terminalStreamPosition]?.let(descriptor::withStreamPosition) ?: descriptor
            }
        }
    return StoredReceipt(
        fingerprint = row[IdempotencyReceipts.fingerprint],
        ownerTokenDigest = row[IdempotencyReceipts.ownerTokenDigest],
        leaseDeadline = row[IdempotencyReceipts.leaseDeadline],
        descriptor = descriptor,
    )
}

private fun validateTiming(
    lease: Duration,
    commandTimeout: Duration,
) {
    lease.requireGt(Duration.ZERO, "lease")
    commandTimeout.requireGt(Duration.ZERO, "commandTimeout")
    commandTimeout.requireLt(lease, "commandTimeout")
}
