package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.workshop.commerce.voucher.admission.AdmissionDecision
import io.bluetape4k.workshop.commerce.voucher.admission.VoucherAdmissionGate
import io.bluetape4k.workshop.commerce.voucher.admission.VoucherAdmissionKeyFactory
import io.bluetape4k.workshop.commerce.voucher.application.IdempotentCommandResult
import io.bluetape4k.workshop.commerce.voucher.application.IdempotentVoucherCommand
import io.bluetape4k.workshop.commerce.voucher.application.IdempotentVoucherCommandService
import io.bluetape4k.workshop.commerce.voucher.application.RetryableVoucherCommand
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCommandException
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCommandFailure
import io.bluetape4k.workshop.commerce.voucher.idempotency.Digest
import io.bluetape4k.workshop.commerce.voucher.idempotency.IdempotencyFingerprint
import io.bluetape4k.workshop.commerce.voucher.idempotency.IdempotencyScope
import io.bluetape4k.workshop.commerce.voucher.idempotency.StoredHttpResponse
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherResponseKind
import io.bluetape4k.workshop.commerce.voucher.config.VoucherMetrics
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID
import kotlin.math.ceil

internal data class ExecutedHttpCommand(
    val response: StoredHttpResponse,
    val replayed: Boolean,
)

/** application-owned idempotency contract를 닫힌 HTTP response descriptor에 맞게 adapter 처리합니다. */
@Component
internal class VoucherHttpCommandExecutor(
    private val commands: IdempotentVoucherCommandService,
    private val admission: VoucherAdmissionGate,
    private val admissionKeys: VoucherAdmissionKeyFactory,
    private val metrics: VoucherMetrics? = null,
) {
    fun execute(
        tenantId: String,
        principalRef: String,
        rawIdempotencyKey: String,
        operation: String,
        resourceId: UUID,
        fingerprintMaterial: String,
        business: () -> StoredHttpResponse,
    ): ExecutedHttpCommand =
        executeScoped(
            tenantId,
            principalRef,
            rawIdempotencyKey,
            operation,
            resourceId,
            fingerprintMaterial,
            externalBusiness = false,
            business,
        )

    fun executeExternal(
        tenantId: String,
        principalRef: String,
        rawIdempotencyKey: String,
        operation: String,
        resourceId: UUID,
        fingerprintMaterial: String,
        business: () -> StoredHttpResponse,
    ): ExecutedHttpCommand =
        executeScoped(
            tenantId,
            principalRef,
            rawIdempotencyKey,
            operation,
            resourceId,
            fingerprintMaterial,
            externalBusiness = true,
            business,
        )

    private fun executeScoped(
        tenantId: String,
        principalRef: String,
        rawIdempotencyKey: String,
        operation: String,
        resourceId: UUID,
        fingerprintMaterial: String,
        externalBusiness: Boolean,
        business: () -> StoredHttpResponse,
    ): ExecutedHttpCommand {
        val startedAt = System.nanoTime()
        var outcome = "FAILED"
        try {
            val executed = executeCommand(
                tenantId,
                principalRef,
                rawIdempotencyKey,
                operation,
                resourceId,
                fingerprintMaterial,
                externalBusiness,
                business,
            )
            outcome = executed.response.responseKind.name
            return executed
        } finally {
            metrics?.recordCommand(operation, outcome, Duration.ofNanos(System.nanoTime() - startedAt))
        }
    }

    private fun executeCommand(
        tenantId: String,
        principalRef: String,
        rawIdempotencyKey: String,
        operation: String,
        resourceId: UUID,
        fingerprintMaterial: String,
        externalBusiness: Boolean,
        business: () -> StoredHttpResponse,
    ): ExecutedHttpCommand {
        val key = requireAsciiIdentifier(rawIdempotencyKey, IDEMPOTENCY_HEADER)
        if (key.length < MINIMUM_IDEMPOTENCY_KEY_LENGTH) {
            throw invalidRequest("Idempotency-Key must contain at least $MINIMUM_IDEMPOTENCY_KEY_LENGTH characters")
        }
        val principalDigest = Digest.sha256("voucher-principal-v1\u0000$tenantId\u0000$principalRef")
        val scope =
            IdempotencyScope(
                tenantId = tenantId,
                principalDigest = principalDigest,
                operation = operation,
                resourceId = resourceId.toString(),
                keyDigest = IdempotencyFingerprint.key(tenantId, principalDigest, operation, resourceId.toString(), key),
            )
        val command =
            IdempotentVoucherCommand(
                scope = scope,
                fingerprint = Digest.sha256("voucher-http-command-v1\u0000$operation\u0000$fingerprintMaterial"),
            )
        val admissionCheck = { admissionResponse(tenantId, principalRef, operation, resourceId) }
        val guardedBusiness = {
            try {
                business()
            } catch (failure: VoucherCommandException) {
                val response = failureResponse(failure.code, resourceId)
                if (failure.code in RETRYABLE_COMMAND_FAILURES) throw RetryableVoucherCommand(response)
                response
            }
        }
        val result =
            if (externalBusiness) {
                commands.executeExternal(command, admissionCheck, guardedBusiness)
            } else {
                commands.execute(command, admissionCheck, guardedBusiness)
            }
        return when (result) {
            is IdempotentCommandResult.Completed -> ExecutedHttpCommand(result.response, result.replayed)
            is IdempotentCommandResult.Retryable -> ExecutedHttpCommand(result.response, replayed = false)
            is IdempotentCommandResult.InProgress ->
                throw VoucherApiException(
                    "COMMAND_IN_PROGRESS",
                    409,
                    "the command is already in progress",
                    result.retryAfter.toRetrySeconds(),
                )
            IdempotentCommandResult.FingerprintConflict ->
                throw VoucherApiException(
                    "IDEMPOTENCY_FINGERPRINT_CONFLICT",
                    409,
                    "the idempotency key belongs to a different request",
                )
        }
    }

    private fun admissionResponse(
        tenantId: String,
        principalRef: String,
        operation: String,
        resourceId: UUID,
    ): StoredHttpResponse? =
        when (val decision = admission.decide(admissionKeys.rateKey(tenantId, principalRef, operation))) {
            AdmissionDecision.Proceed -> null
            is AdmissionDecision.RateLimited ->
                retryable(
                    VoucherResponseKind.RATE_LIMITED,
                    429,
                    resourceId,
                    decision.retryAfter,
                )
            is AdmissionDecision.DatabaseBusy ->
                retryable(
                    VoucherResponseKind.DATABASE_BULKHEAD_REJECTED,
                    503,
                    resourceId,
                    decision.retryAfter,
                )
        }

    private fun failureResponse(
        failure: VoucherCommandFailure,
        resourceId: UUID,
    ): StoredHttpResponse {
        val (kind, status) = FAILURE_RESPONSES.getValue(failure)
        val headers = if (failure in RETRYABLE_COMMAND_FAILURES) mapOf("Retry-After" to "1") else emptyMap()
        return StoredHttpResponse(kind, status, headers, resourceId, null, 0, null, null)
    }

    private fun retryable(
        kind: VoucherResponseKind,
        status: Int,
        resourceId: UUID,
        retryAfter: Duration,
    ): StoredHttpResponse =
        StoredHttpResponse(
            responseKind = kind,
            status = status,
            headers = mapOf("Retry-After" to retryAfter.toRetrySeconds().toString()),
            aggregateId = resourceId,
            allocationId = null,
            aggregateRevision = 0,
            generationKeyVersion = null,
            verificationKeyVersion = null,
        )

    private fun Duration.toRetrySeconds(): Long = ceil(toMillis() / 1_000.0).toLong().coerceAtLeast(1)

    companion object {
        private const val MINIMUM_IDEMPOTENCY_KEY_LENGTH = 8
        private val RETRYABLE_COMMAND_FAILURES =
            setOf(VoucherCommandFailure.CAMPAIGN_PAUSED, VoucherCommandFailure.REPLAY_KEY_UNAVAILABLE)
        private val FAILURE_RESPONSES =
            mapOf(
                VoucherCommandFailure.CAMPAIGN_ALREADY_EXISTS to (VoucherResponseKind.CAMPAIGN_ALREADY_EXISTS to 409),
                VoucherCommandFailure.CAMPAIGN_NOT_FOUND to (VoucherResponseKind.CAMPAIGN_NOT_FOUND to 404),
                VoucherCommandFailure.CLAIM_NOT_FOUND to (VoucherResponseKind.CLAIM_NOT_FOUND to 404),
                VoucherCommandFailure.REVIEW_NOT_FOUND to (VoucherResponseKind.REVIEW_NOT_FOUND to 404),
                VoucherCommandFailure.CAMPAIGN_PAUSED to (VoucherResponseKind.CAMPAIGN_PAUSED to 409),
                VoucherCommandFailure.CAMPAIGN_NOT_ACTIVE to (VoucherResponseKind.CAMPAIGN_NOT_ACTIVE to 409),
                VoucherCommandFailure.CAMPAIGN_NOT_STARTED to (VoucherResponseKind.CAMPAIGN_NOT_STARTED to 409),
                VoucherCommandFailure.CAMPAIGN_ENDED to (VoucherResponseKind.CAMPAIGN_ENDED to 409),
                VoucherCommandFailure.CAPACITY_EXHAUSTED to (VoucherResponseKind.CAPACITY_EXHAUSTED to 409),
                VoucherCommandFailure.PER_USER_LIMIT_REACHED to (VoucherResponseKind.PER_USER_LIMIT_REACHED to 409),
                VoucherCommandFailure.INVALID_CODE to (VoucherResponseKind.INVALID_CODE to 409),
                VoucherCommandFailure.VOUCHER_EXPIRED to (VoucherResponseKind.CLAIM_EXPIRED to 409),
                VoucherCommandFailure.ALREADY_REDEEMED to (VoucherResponseKind.ALREADY_REDEEMED to 409),
                VoucherCommandFailure.STALE_REVISION to (VoucherResponseKind.STALE_REVISION to 412),
                VoucherCommandFailure.CONCURRENT_MODIFICATION to (VoucherResponseKind.CONCURRENT_MODIFICATION to 409),
                VoucherCommandFailure.REPLAY_KEY_UNAVAILABLE to
                    (VoucherResponseKind.IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE to 503),
                VoucherCommandFailure.CODE_ALREADY_ACKNOWLEDGED to
                    (VoucherResponseKind.CODE_ALREADY_ACKNOWLEDGED to 409),
            )
    }
}
