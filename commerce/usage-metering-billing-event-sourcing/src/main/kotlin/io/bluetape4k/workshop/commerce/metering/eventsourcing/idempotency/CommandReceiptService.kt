package io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency

import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptCompletion
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptInsert
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptSnapshot
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptStatus
import java.time.Duration
import java.time.Instant
import java.util.*

data class CommandScope(val tenantId: String, val operation: String, val keyDigest: CommandDigest)

sealed interface CommandAcquireResult {
    data class Owned(val receiptId: UUID, val ownerToken: UUID): CommandAcquireResult
    data class Replay(val httpStatus: Int, val response: String): CommandAcquireResult
    data class InProgress(val retryAfter: Duration): CommandAcquireResult
    data object Conflict: CommandAcquireResult
}

class CommandOwnerLostException: IllegalStateException("command_receipt_owner_lost")

class CommandReceiptService(
    private val repository: CommandReceiptRepository,
    private val lease: Duration,
    private val retention: Duration,
) {

    private companion object: KLogging() {
        const val MAX_RESPONSE_BYTES = 16 * 1024
    }

    init {
        lease.requirePositiveDuration("lease")
        retention.requireGt(lease, "retention")
    }

    fun acquire(scope: CommandScope, fingerprint: CommandDigest, now: Instant): CommandAcquireResult {
        // ownerToken은 takeover 방지용 opaque 보안 토큰이므로 UUID v7로 정렬하지 않는다.
        val ownerToken = UUID.randomUUID()
        repository.insertOwnerIfAbsent(
            CommandReceiptInsert(
                scope = scope,
                fingerprint = fingerprint,
                ownerToken = ownerToken,
                leaseUntil = now.plus(lease),
                retentionUntil = now.plus(retention),
                now = now
            ),
        )

        val current = repository.find(scope).checkNotNull { "command_receipt_not_found" }

        return when {
            current.fingerprint != fingerprint               ->
                CommandAcquireResult.Conflict
            current.status == CommandReceiptStatus.SUCCEEDED ->
                CommandAcquireResult.Replay(current.httpStatus!!, current.response!!)
            current.leaseUntil.isAfter(now)                  ->
                activeLeaseResult(current, ownerToken, now)
            else                                             ->
                takeoverResult(current, now)
        }
    }

    private fun activeLeaseResult(
        current: CommandReceiptSnapshot,
        attemptedOwner: UUID,
        now: Instant,
    ): CommandAcquireResult =
        if (current.ownerToken == attemptedOwner) {
            CommandAcquireResult.Owned(current.id, attemptedOwner)
        } else {
            CommandAcquireResult.InProgress(Duration.between(now, current.leaseUntil))
        }

    private fun takeoverResult(
        current: CommandReceiptSnapshot,
        now: Instant,
    ): CommandAcquireResult {
        // replacement도 기존 owner를 fencing하는 opaque 보안 토큰이며 documented raw fallback이다.
        val replacement = UUID.randomUUID()

        val takeovered = repository.takeover(
            current = current,
            owner = replacement,
            leaseUntil = now.plus(lease),
            retentionUntil = now.plus(retention),
            now = now
        )

        return if (takeovered) {
            CommandAcquireResult.Owned(current.id, replacement)
        } else {
            CommandAcquireResult.InProgress(lease)
        }
    }

    fun succeed(owned: CommandAcquireResult.Owned, httpStatus: Int, response: String, now: Instant): Boolean {
        require(response.toUtf8Bytes().size <= MAX_RESPONSE_BYTES) { "command_response_too_large" }
        return repository.complete(
            CommandReceiptCompletion(
                receiptId = owned.receiptId,
                owner = owned.ownerToken,
                httpStatus = httpStatus,
                response = response,
                retentionUntil = now.plus(retention),
                now = now
            ),
        )
    }

    fun requireOwnership(owned: CommandAcquireResult.Owned, now: Instant) {
        if (!repository.isActiveOwner(owned.receiptId, owned.ownerToken, now)) {
            throw CommandOwnerLostException()
        }
    }
}
