package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.checkBeTrue
import io.bluetape4k.support.requireInRange
import io.bluetape4k.workshop.commerce.reservation.idempotency.AcquireResult
import io.bluetape4k.workshop.commerce.reservation.idempotency.HttpIdempotencyRepository
import io.bluetape4k.workshop.commerce.reservation.idempotency.IdempotencyFingerprint
import io.bluetape4k.workshop.commerce.reservation.idempotency.IdempotencyScope
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.io.Serializable
import java.time.Clock

internal data class IdempotentCommandResult<T>(
    val status: Int,
    val value: T,
    val replayed: Boolean,
): Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** command mutation과 replay 가능한 HTTP body를 단일 PostgreSQL transaction에 저장합니다. */
@Service
internal class IdempotentReservationCommandService(
    private val repository: HttpIdempotencyRepository,
    private val credentials: ReservationCredentialService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    @Value("\${reservation.tenant-id}") private val tenantId: String,
) {
    companion object: KLogging()
    
    @Transactional
    fun <T: Any> execute(
        operation: String,
        rawKey: String,
        rawOwner: String,
        canonicalPayload: String,
        successStatus: Int,
        bodyType: Class<T>,
        action: () -> T,
    ): IdempotentCommandResult<T> {
        rawKey.length.requireInRange(16, 200, "rawKey.length")

        val ownerDigest = credentials.ownerDigest(rawOwner)
        val scope = IdempotencyScope(
            tenantId = tenantId,
            operation = operation,
            keyDigest = credentials.idempotencyDigest(tenantId, operation, rawKey)
        )
        val fingerprint = IdempotencyFingerprint.request(operation, "$canonicalPayload\nowner=$ownerDigest")
        val ownerToken = Uuid.V7.nextId()

        val acquired = repository.acquire(scope, fingerprint, ownerToken, clock.instant())
        return when (acquired) {
            is AcquireResult.New,
            is AcquireResult.Takeover,
                                        -> {
                val record =
                    when (acquired) {
                        is AcquireResult.New      -> acquired.record
                        is AcquireResult.Takeover -> acquired.record
                    }
                val response = action()
                val body = objectMapper.writeValueAsString(response)
                repository.finalize(record.id, ownerToken, successStatus, body, failed = false)
                    .checkBeTrue { "idempotency owner lost before command finalize" }
                log.info { "reservation_idempotency_completed operation=$operation status=$successStatus" }
                IdempotentCommandResult(successStatus, response, replayed = false)
            }
            is AcquireResult.Replay     -> {
                log.info { "reservation_idempotency_replayed operation=$operation status=${acquired.status}" }
                IdempotentCommandResult(
                    status = acquired.status,
                    value = objectMapper.readValue(acquired.body, bodyType),
                    replayed = true
                )
            }
            AcquireResult.FingerprintConflict -> {
                log.warn { "reservation_idempotency_rejected operation=$operation reason=FINGERPRINT_CONFLICT" }
                throw ReservationCommandException("IDEMPOTENCY_FINGERPRINT_CONFLICT", null, false)
            }
            is AcquireResult.InProgress -> {
                val retryAfter = acquired.retryAfter.seconds.coerceAtLeast(1)
                log.warn { "reservation_idempotency_rejected operation=$operation reason=COMMAND_IN_PROGRESS" }
                throw ReservationCommandException("COMMAND_IN_PROGRESS", null, true, retryAfter)
            }
        }
    }
}
