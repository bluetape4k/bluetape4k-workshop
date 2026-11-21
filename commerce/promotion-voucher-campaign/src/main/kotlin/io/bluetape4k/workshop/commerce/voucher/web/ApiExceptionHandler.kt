package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitRejected
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherResponseKind
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import kotlin.math.ceil

@RestControllerAdvice
/**
 * ApiExceptionHandler은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class ApiExceptionHandler {

    companion object: KLogging()

    /**
     * `voucherFailure`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = voucherFailure(failure = currentVoucherFailure, request = currentHttpRequest)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(VoucherApiException::class)
    fun voucherFailure(
        failure: VoucherApiException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> =
        errorResponse(
            status = failure.status,
            code = failure.stableCode,
            reason = failure.safeReason,
            requestId = request.requestId(),
            retryAfterSeconds = failure.retryAfterSeconds,
        )

    /**
     * `databaseBusy`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = databaseBusy(failure = currentDatabaseRejection, request = currentHttpRequest)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(DatabasePermitRejected::class)
    fun databaseBusy(
        failure: DatabasePermitRejected,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> =
        errorResponse(
            status = 503,
            code = "DATABASE_BULKHEAD_REJECTED",
            reason = "database capacity is temporarily unavailable",
            requestId = request.requestId(),
            retryAfterSeconds = ceil(failure.retryAfter.toMillis() / 1_000.0).toLong().coerceAtLeast(1),
        )

    /**
     * `sseCapacity`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = sseCapacity(failure = currentSseRejection, request = currentHttpRequest)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(SseCapacityRejected::class)
    fun sseCapacity(
        failure: SseCapacityRejected,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        val fallback = "/api/v1/campaigns/${failure.campaignId}"
        val headers = HttpHeaders()
        headers.set("Retry-After", "2")
        headers.set(HttpHeaders.LINK, "<$fallback>; rel=\"alternate\"; type=\"application/json\"")

        return ResponseEntity
            .status(503)
            .headers(headers)
            .body(
                ApiError(
                    "SSE_CAPACITY_REJECTED",
                    "event stream capacity is temporarily unavailable",
                    request.requestId(),
                    2,
                ),
            )
    }

    /**
     * `shuttingDown`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = shuttingDown(request = currentHttpRequest)
     * ```
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(VoucherServiceShuttingDown::class)
    fun shuttingDown(
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> =
        errorResponse(
            status = 503,
            code = "SERVICE_SHUTTING_DOWN",
            reason = "service is shutting down",
            requestId = request.requestId(),
            retryAfterSeconds = 1
        )

    /**
     * `invalidInput`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = invalidInput(
     *     failure = IllegalArgumentException("missing idempotency key"),
     *     request = currentHttpRequest,
     * )
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @param request correlation id와 요청 메타데이터를 제공하는 현재 HTTP 요청입니다.
     * @return 400 `INVALID_REQUEST` 오류와 correlation 정보를 반환합니다.
     */
    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
        MissingRequestHeaderException::class,
        MethodArgumentTypeMismatchException::class,
        IllegalArgumentException::class,
    )
    /**
     * invalidInput은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * 예: 잘못된 voucher command는 `400`과 안정적인 오류 코드를 반환해 caller가 입력을 수정할 수 있게 합니다.
     */
    fun invalidInput(
        failure: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        log.warn { "voucher_http_rejected category=INVALID_REQUEST failure=${failure.javaClass.simpleName}" }

        return errorResponse(
            status = 400,
            code = "INVALID_REQUEST",
            reason = "request validation failed",
            requestId = request.requestId(),
            retryAfterSeconds = null
        )
    }

    /**
     * `missingResource`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = missingResource(failure = currentNotFound, request = currentHttpRequest)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun missingResource(
        failure: NoResourceFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        log.warn { "voucher_http_rejected category=NOT_FOUND failure=${failure.javaClass.simpleName}" }

        return errorResponse(
            status = 404,
            code = "RESOURCE_NOT_FOUND",
            reason = "resource was not found",
            requestId = request.requestId(),
            retryAfterSeconds = null
        )
    }

    /**
     * `unexpected`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = unexpected(failure = currentException, request = currentHttpRequest)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(Exception::class)
    fun unexpected(
        failure: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        log.warn { "voucher_http_failed category=INTERNAL_ERROR failure=${failure.javaClass.simpleName}" }

        return errorResponse(
            status = 500,
            code = "INTERNAL_ERROR",
            reason = "request could not be completed",
            requestId = request.requestId(),
            retryAfterSeconds = null
        )
    }
}

/**
 * executedResponse은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param executed 업무 처리에 필요한 executed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
 * @param successBody 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
 */
internal fun executedResponse(
    executed: ExecutedHttpCommand,
    request: HttpServletRequest,
    successBody: () -> Any,
): ResponseEntity<Any> {
    val stored = executed.response
    val headers = HttpHeaders()
    stored.headers
        .filterKeys { !it.isStoredDescriptorHeader() }
        .forEach(headers::set)

    headers.set("Idempotency-Replayed", executed.replayed.toString())

    val body = if (stored.status >= 400) {
        ApiError(
            code = stored.responseKind.name,
            reason = stored.responseKind.safeReason(),
            requestId = request.requestId(),
            retryAfterSeconds = stored.headers["Retry-After"]?.toLongOrNull(),
        )
    } else {
        successBody()
    }
    return ResponseEntity
        .status(stored.status)
        .headers(headers)
        .body(body)
}

/**
 * safeReason은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun VoucherResponseKind.safeReason(): String =
    name.lowercase().replace('_', ' ')

/**
 * errorResponse은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param retryAfterSeconds 업무 처리에 필요한 retryAfterSeconds 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun errorResponse(
    status: Int,
    code: String,
    reason: String,
    requestId: String,
    retryAfterSeconds: Long?,
): ResponseEntity<ApiError> {
    val headers = HttpHeaders()
    retryAfterSeconds?.let { headers.set("Retry-After", it.toString()) }
    return ResponseEntity
        .status(status)
        .headers(headers)
        .body(ApiError(code, reason, requestId, retryAfterSeconds))
}

/**
 * requestId은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun HttpServletRequest.requestId(): String =
    getAttribute(REQUEST_ID_ATTRIBUTE) as? String ?: newRequestId()
