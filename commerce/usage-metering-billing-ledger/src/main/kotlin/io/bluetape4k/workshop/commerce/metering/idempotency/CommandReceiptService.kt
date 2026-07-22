package io.bluetape4k.workshop.commerce.metering.idempotency

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.support.requireInRange
import io.bluetape4k.workshop.commerce.metering.config.MeteringProperties
import io.bluetape4k.workshop.commerce.metering.domain.CommandReceiptStatus
import io.bluetape4k.workshop.commerce.metering.persistence.CommandReceiptRepository
import io.bluetape4k.workshop.commerce.metering.persistence.CommandReceiptCompletion
import io.bluetape4k.workshop.commerce.metering.persistence.CommandReceiptOwnerInsert
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class CommandReceiptScope(
    val tenantId: String,
    val operation: String,
    val keyDigest: String,
) {
    init {
        tenantId.length.requireInRange(1, MAX_SCOPE_PART_LENGTH, "tenantId.length")
        operation.length.requireInRange(1, MAX_SCOPE_PART_LENGTH, "operation.length")
        keyDigest.length.requireInRange(SHA_256_HEX_LENGTH, SHA_256_HEX_LENGTH, "keyDigest.length")
    }
}

data class CommandReceiptSnapshot(
    val id: UUID,
    val requestFingerprint: String,
    val status: CommandReceiptStatus,
    val ownerToken: UUID,
    val leaseDeadline: Instant,
    val terminalHttpStatus: Int?,
    val terminalResponse: String?,
)

private const val MAX_SCOPE_PART_LENGTH = 64
private const val SHA_256_HEX_LENGTH = 64
private const val MIN_HTTP_STATUS = 100
private const val MAX_HTTP_STATUS = 599
private const val DEFAULT_CLEANUP_BATCH_SIZE = 200
private const val MAX_CLEANUP_BATCH_SIZE = 1_000

sealed interface CommandAcquireResult {
    data class Acquired(
        val receipt: CommandReceiptSnapshot,
        val takeover: Boolean,
    ) : CommandAcquireResult

    data class Replay(
        val httpStatus: Int,
        val response: String,
        val failed: Boolean,
    ) : CommandAcquireResult

    data class InProgress(val retryAfter: Duration) : CommandAcquireResult

    data object Conflict : CommandAcquireResult
}

@Service
class CommandReceiptService(
    private val repository: CommandReceiptRepository,
    private val properties: MeteringProperties,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Suppress("ReturnCount") // Each branch is a distinct receipt state-machine transition.
    fun acquire(
        scope: CommandReceiptScope,
        requestFingerprint: Sha256Digest,
        ownerToken: UUID = Uuid.V7.nextId(),
    ): CommandAcquireResult {
        val now = clock.instant()
        val receiptProperties = properties.commandReceipt
        val inserted =
            repository.insertOwnerIfAbsent(CommandReceiptOwnerInsert(
                scope = scope,
                requestFingerprint = requestFingerprint.value,
                ownerToken = ownerToken,
                now = now,
                leaseDeadline = now.plus(receiptProperties.lease),
                retentionDeadline = now.plus(receiptProperties.retention),
            ))
        var current = repository.find(scope) ?: error("command receipt was not persisted")
        if (inserted) return CommandAcquireResult.Acquired(current, takeover = false)
        if (current.requestFingerprint != requestFingerprint.value) return CommandAcquireResult.Conflict
        if (current.status != CommandReceiptStatus.IN_PROGRESS) {
            return CommandAcquireResult.Replay(
                httpStatus = requireNotNull(current.terminalHttpStatus),
                response = requireNotNull(current.terminalResponse),
                failed = current.status == CommandReceiptStatus.FAILED,
            )
        }
        if (current.leaseDeadline > now) {
            return CommandAcquireResult.InProgress(Duration.between(now, current.leaseDeadline))
        }

        val taken =
            repository.takeover(
                current = current,
                ownerToken = ownerToken,
                now = now,
                leaseDeadline = now.plus(receiptProperties.lease),
                retentionDeadline = now.plus(receiptProperties.retention),
            )
        current = repository.find(scope) ?: error("command receipt disappeared during takeover")
        return if (taken) {
            CommandAcquireResult.Acquired(current, takeover = true)
        } else {
            CommandAcquireResult.InProgress(Duration.between(now, current.leaseDeadline).coerceAtLeast(Duration.ZERO))
        }
    }

    @Transactional
    fun succeed(receiptId: UUID, ownerToken: UUID, httpStatus: Int, response: String): Boolean =
        complete(receiptId, ownerToken, CommandReceiptStatus.SUCCEEDED, httpStatus, response)

    @Transactional
    fun fail(receiptId: UUID, ownerToken: UUID, httpStatus: Int, response: String): Boolean =
        complete(receiptId, ownerToken, CommandReceiptStatus.FAILED, httpStatus, response)

    private fun complete(
        receiptId: UUID,
        ownerToken: UUID,
        status: CommandReceiptStatus,
        httpStatus: Int,
        response: String,
    ): Boolean {
        response.toByteArray(UTF_8).size.requireInRange(
            0,
            properties.commandReceipt.terminalResponseBytes,
            "terminalResponse.bytes",
        )
        httpStatus.requireInRange(MIN_HTTP_STATUS, MAX_HTTP_STATUS, "httpStatus")
        val now = clock.instant()
        return repository.complete(CommandReceiptCompletion(
            receiptId = receiptId,
            ownerToken = ownerToken,
            status = status,
            httpStatus = httpStatus,
            response = response,
            now = now,
            retentionDeadline = now.plus(properties.commandReceipt.retention),
        ))
    }

    @Transactional
    fun cleanupExpiredTerminal(limit: Int = DEFAULT_CLEANUP_BATCH_SIZE): Int {
        limit.requireInRange(1, MAX_CLEANUP_BATCH_SIZE, "limit")
        return repository.deleteExpiredTerminalBatch(clock.instant(), limit)
    }
}

private fun Duration.coerceAtLeast(minimum: Duration): Duration = if (this < minimum) minimum else this
