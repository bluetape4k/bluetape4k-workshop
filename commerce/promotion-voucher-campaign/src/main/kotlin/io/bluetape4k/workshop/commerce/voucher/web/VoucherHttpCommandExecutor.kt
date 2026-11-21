package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.javatimes.nanos
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.admission.AdmissionDecision
import io.bluetape4k.workshop.commerce.voucher.admission.VoucherAdmissionGate
import io.bluetape4k.workshop.commerce.voucher.admission.VoucherAdmissionKeyFactory
import io.bluetape4k.workshop.commerce.voucher.application.IdempotentCommandResult
import io.bluetape4k.workshop.commerce.voucher.application.IdempotentVoucherCommand
import io.bluetape4k.workshop.commerce.voucher.application.IdempotentVoucherCommandService
import io.bluetape4k.workshop.commerce.voucher.application.RetryableVoucherCommand
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCommandException
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCommandFailure
import io.bluetape4k.workshop.commerce.voucher.config.VoucherMetrics
import io.bluetape4k.workshop.commerce.voucher.idempotency.Digest
import io.bluetape4k.workshop.commerce.voucher.idempotency.IdempotencyFingerprint
import io.bluetape4k.workshop.commerce.voucher.idempotency.IdempotencyScope
import io.bluetape4k.workshop.commerce.voucher.idempotency.StoredHttpResponse
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherResponseKind
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.*
import kotlin.math.ceil
/**
 * ExecutedHttpCommand은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property response 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property replayed 프로모션 바우처 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 */


internal data class ExecutedHttpCommand(
    /**
     * `response`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val response: StoredHttpResponse,
    /**
     * `replayed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val replayed: Boolean,
)

/**
 * application-owned idempotency contract를 닫힌 HTTP response descriptor에 맞게 adapter 처리합니다.
 * @property commands 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property admission 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property admissionKeys 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property metrics 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Component
internal class VoucherHttpCommandExecutor(
    /**
     * `commands`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val commands: IdempotentVoucherCommandService,
    /**
     * `admission`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val admission: VoucherAdmissionGate,
    /**
     * `admissionKeys`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val admissionKeys: VoucherAdmissionKeyFactory,
    /**
     * `metrics`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: VoucherMetrics? = null,
) {

    companion object: KLogging() {
        /**
         * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
         */
        private const val MINIMUM_IDEMPOTENCY_KEY_LENGTH = 8
        /**
         * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
         */
        private val RETRYABLE_COMMAND_FAILURES = setOf(
            VoucherCommandFailure.CAMPAIGN_PAUSED,
            VoucherCommandFailure.REPLAY_KEY_UNAVAILABLE
        )
        /**
         * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
         */
        private val FAILURE_RESPONSES = mapOf(
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
            VoucherCommandFailure.REPLAY_KEY_UNAVAILABLE to (VoucherResponseKind.IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE to 503),
            VoucherCommandFailure.CODE_ALREADY_ACKNOWLEDGED to (VoucherResponseKind.CODE_ALREADY_ACKNOWLEDGED to 409),
        )
    }

    /**
     * execute은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param principalRef 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param rawIdempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param resourceId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param fingerprintMaterial 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param business 업무 처리에 필요한 business 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
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
            tenantId = tenantId,
            principalRef = principalRef,
            rawIdempotencyKey = rawIdempotencyKey,
            operation = operation,
            resourceId = resourceId,
            fingerprintMaterial = fingerprintMaterial,
            externalBusiness = false,
            business = business,
        )

    /**
     * executeExternal은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param principalRef 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param rawIdempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param resourceId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param fingerprintMaterial 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param business 업무 처리에 필요한 business 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
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
            tenantId = tenantId,
            principalRef = principalRef,
            rawIdempotencyKey = rawIdempotencyKey,
            operation = operation,
            resourceId = resourceId,
            fingerprintMaterial = fingerprintMaterial,
            externalBusiness = true,
            business = business,
        )

    /**
     * executeScoped은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param principalRef 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param rawIdempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param resourceId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param fingerprintMaterial 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param externalBusiness 업무 처리에 필요한 externalBusiness 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param business 업무 처리에 필요한 business 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
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
                tenantId = tenantId,
                principalRef = principalRef,
                rawIdempotencyKey = rawIdempotencyKey,
                operation = operation,
                resourceId = resourceId,
                fingerprintMaterial = fingerprintMaterial,
                externalBusiness = externalBusiness,
                business = business,
            )
            outcome = executed.response.responseKind.name
            return executed
        } finally {
            metrics?.recordCommand(
                operation = operation,
                outcome = outcome,
                duration = (System.nanoTime() - startedAt).nanos()
            )
        }
    }

    /**
     * executeCommand은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param principalRef 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param rawIdempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param resourceId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param fingerprintMaterial 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param externalBusiness 업무 처리에 필요한 externalBusiness 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param business 업무 처리에 필요한 business 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
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

        val scope = IdempotencyScope(
            tenantId = tenantId,
            principalDigest = principalDigest,
            operation = operation,
            resourceId = resourceId.toString(),
            keyDigest = IdempotencyFingerprint.key(
                tenantId,
                principalDigest,
                operation,
                resourceId.toString(),
                key
            ),
        )
        val command = IdempotentVoucherCommand(
            scope = scope,
            fingerprint = Digest.sha256("voucher-http-command-v1\u0000$operation\u0000$fingerprintMaterial"),
        )
        val admissionCheck: () -> StoredHttpResponse? = {
            admissionResponse(tenantId, principalRef, operation, resourceId)
        }
        val guardedBusiness: () -> StoredHttpResponse = {
            try {
                business()
            } catch (failure: VoucherCommandException) {
                val response = failureResponse(failure.code, resourceId)
                if (failure.code in RETRYABLE_COMMAND_FAILURES) throw RetryableVoucherCommand(response)
                response
            }
        }

        val result = if (externalBusiness) {
            commands.executeExternal(command, admissionCheck, guardedBusiness)
        } else {
            commands.execute(command, admissionCheck, guardedBusiness)
        }

        return when (result) {
            is IdempotentCommandResult.Completed        -> ExecutedHttpCommand(result.response, result.replayed)
            is IdempotentCommandResult.Retryable        -> ExecutedHttpCommand(result.response, replayed = false)
            is IdempotentCommandResult.InProgress       ->
                throw VoucherApiException(
                    stableCode = "COMMAND_IN_PROGRESS",
                    status = 409,
                    safeReason = "the command is already in progress",
                    retryAfterSeconds = result.retryAfter.toRetrySeconds(),
                )
            IdempotentCommandResult.FingerprintConflict ->
                throw VoucherApiException(
                    stableCode = "IDEMPOTENCY_FINGERPRINT_CONFLICT",
                    status = 409,
                    safeReason = "the idempotency key belongs to a different request",
                )
        }
    }

    /**
     * admissionResponse은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param principalRef 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param resourceId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun admissionResponse(
        tenantId: String,
        principalRef: String,
        operation: String,
        resourceId: UUID,
    ): StoredHttpResponse? {
        val rateKey = admissionKeys.rateKey(tenantId, principalRef, operation)

        return when (val decision = admission.decide(rateKey)) {
            AdmissionDecision.Proceed         -> null
            is AdmissionDecision.RateLimited  ->
                retryable(
                    kind = VoucherResponseKind.RATE_LIMITED,
                    status = 429,
                    resourceId = resourceId,
                    retryAfter = decision.retryAfter,
                )
            is AdmissionDecision.DatabaseBusy ->
                retryable(
                    kind = VoucherResponseKind.DATABASE_BULKHEAD_REJECTED,
                    status = 503,
                    resourceId = resourceId,
                    retryAfter = decision.retryAfter,
                )
        }
    }

    /**
     * failureResponse은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param resourceId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun failureResponse(
        failure: VoucherCommandFailure,
        resourceId: UUID,
    ): StoredHttpResponse {
        val (kind, status) = FAILURE_RESPONSES.getValue(failure)
        val headers = if (failure in RETRYABLE_COMMAND_FAILURES) mapOf("Retry-After" to "1") else emptyMap()

        return StoredHttpResponse(
            responseKind = kind,
            status = status,
            headers = headers,
            aggregateId = resourceId,
            allocationId = null,
            aggregateRevision = 0,
            generationKeyVersion = null,
            verificationKeyVersion = null
        )
    }

    /**
     * retryable은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param resourceId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param retryAfter 업무 처리에 필요한 retryAfter 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
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

    /**
     * toRetrySeconds은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun Duration.toRetrySeconds(): Long =
        ceil(toMillis() / 1_000.0).toLong().coerceAtLeast(1)

}
