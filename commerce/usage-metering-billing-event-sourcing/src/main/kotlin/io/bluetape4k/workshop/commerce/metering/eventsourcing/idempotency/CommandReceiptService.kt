package io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency

import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptInsert
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptStatus
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptCompletion
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class CommandScope(val tenantId: String, val operation: String, val keyDigest: CommandDigest)

sealed interface CommandAcquireResult {
    data class Owned(val receiptId: UUID, val ownerToken: UUID) : CommandAcquireResult
    data class Replay(val httpStatus: Int, val response: String) : CommandAcquireResult
    data class InProgress(val retryAfter: Duration) : CommandAcquireResult
    data object Conflict : CommandAcquireResult
}

class CommandOwnerLostException : IllegalStateException("command_receipt_owner_lost")

class CommandReceiptService(
    private val repository: CommandReceiptRepository,
    private val lease: Duration,
    private val retention: Duration,
) {
    init {
        require(lease > Duration.ZERO) { "command_lease_invalid" }
        require(retention > lease) { "command_retention_invalid" }
    }

    fun acquire(scope: CommandScope, fingerprint: CommandDigest, now: Instant): CommandAcquireResult {
        val ownerToken = UUID.randomUUID()
        repository.insertOwnerIfAbsent(
            CommandReceiptInsert(scope, fingerprint, ownerToken, now.plus(lease), now.plus(retention), now),
        )
        val current = checkNotNull(repository.find(scope)) { "command_receipt_not_found" }
        return when {
            current.fingerprint != fingerprint -> CommandAcquireResult.Conflict
            current.status == CommandReceiptStatus.SUCCEEDED ->
                CommandAcquireResult.Replay(checkNotNull(current.httpStatus), checkNotNull(current.response))
            current.leaseUntil.isAfter(now) -> activeLeaseResult(current, ownerToken, now)
            else -> takeoverResult(current, now)
        }
    }

    private fun activeLeaseResult(
        current: io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptSnapshot,
        attemptedOwner: UUID,
        now: Instant,
    ): CommandAcquireResult = if (current.ownerToken == attemptedOwner) {
        CommandAcquireResult.Owned(current.id, attemptedOwner)
    } else {
        CommandAcquireResult.InProgress(Duration.between(now, current.leaseUntil))
    }

    private fun takeoverResult(
        current: io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptSnapshot,
        now: Instant,
    ): CommandAcquireResult {
        val replacement = UUID.randomUUID()
        return if (repository.takeover(current, replacement, now.plus(lease), now.plus(retention), now)) {
            CommandAcquireResult.Owned(current.id, replacement)
        } else {
            CommandAcquireResult.InProgress(lease)
        }
    }

    fun succeed(owned: CommandAcquireResult.Owned, httpStatus: Int, response: String, now: Instant): Boolean {
        require(response.toByteArray(UTF_8).size <= MAX_RESPONSE_BYTES) { "command_response_too_large" }
        return repository.complete(
            CommandReceiptCompletion(owned.receiptId, owned.ownerToken, httpStatus, response, now.plus(retention), now),
        )
    }

    fun requireOwnership(owned: CommandAcquireResult.Owned, now: Instant) {
        if (!repository.isActiveOwner(owned.receiptId, owned.ownerToken, now)) {
            throw CommandOwnerLostException()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 16 * 1024
    }
}
