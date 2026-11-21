package io.bluetape4k.workshop.commerce.reservation.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCommandException
import io.bluetape4k.workshop.commerce.reservation.application.WaitlistCommandException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.io.Serializable
import java.time.Instant

/** credential을 누출하지 않고 안정적인 command rejection code를 retry-aware HTTP problem response로 매핑합니다. */
/** credential을 누출하지 않고 안정적인 command rejection code를 retry-aware HTTP problem response로 매핑합니다. */
@RestControllerAdvice
internal class ApiExceptionHandler {

    companion object: KLogging()
    /**
     * `reservation`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = reservation(ex = currentReservationException, request = currentHttpRequest)
     * ```
     * @param ex 요청 경계에서 검증된 `ex`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(ReservationCommandException::class)
    fun reservation(
        ex: ReservationCommandException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        val status = when (ex.reason) {
            "OWNER_MISMATCH"     -> HttpStatus.FORBIDDEN
            "HOLD_EXPIRED"       -> HttpStatus.GONE
            "ADMISSION_REJECTED" -> HttpStatus.TOO_MANY_REQUESTS
            else                 -> HttpStatus.CONFLICT
        }
        log.debug { "reservation_http_rejected reason=${ex.reason} status=${status.value()}" }
        val response = ResponseEntity.status(status)
        ex.retryAfterSeconds?.let { response.header("Retry-After", it.toString()) }

        return response
            .body(error(ex.reason, request, ex.retryable, ex.currentRevision, ex.retryAfterSeconds))
    }

    /**
     * `waitlist`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = waitlist(ex = currentWaitlistException, request = currentHttpRequest)
     * ```
     * @param ex 요청 경계에서 검증된 `ex`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(WaitlistCommandException::class)
    fun waitlist(
        ex: WaitlistCommandException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        val status = when (ex.reason) {
            "OWNER_MISMATCH" -> HttpStatus.FORBIDDEN
            "OFFER_EXPIRED"  -> HttpStatus.GONE
            else             -> HttpStatus.CONFLICT
        }
        log.debug { "reservation_http_rejected reason=${ex.reason} status=${status.value()}" }

        return ResponseEntity
            .status(status)
            .body(error(ex.reason, request, false, ex.currentRevision))
    }

    /**
     * `malformed`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = malformed(ex = currentMalformedRequest, request = currentHttpRequest)
     * ```
     * @param ex 요청 경계에서 검증된 `ex`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(IllegalArgumentException::class, MethodArgumentNotValidException::class)
    fun malformed(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        log.debug { "reservation_http_rejected reason=MALFORMED_REQUEST status=400" }
        return ResponseEntity
            .badRequest()
            .body(error("MALFORMED_REQUEST", request, false, null))
    }

    /**
     * error은 예약 제어면의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param request 예약 제어면 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param retryable 업무 처리에 필요한 retryable 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param revision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param retryAfterSeconds 업무 처리에 필요한 retryAfterSeconds 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun error(
        reason: String,
        request: HttpServletRequest,
        retryable: Boolean,
        revision: Long?,
        retryAfterSeconds: Long? = null,
    ): ApiError =
        ApiError(
            code = reason,
            reason = reason,
            requestId = request.getAttribute(RequestLoggingFilter.REQUEST_ID_ATTRIBUTE)?.toString()
                ?: request.requestId,
            retryable = retryable,
            currentRevision = revision,
            observedAt = Instant.now(),
            retryAfterSeconds = retryAfterSeconds
        )
}
/**
 * ApiError은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property code 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property reason 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property requestId 요청 추적 식별자입니다. API 오류·로그·이벤트를 하나의 업무 시도로 연결하되 비즈니스 식별자와 혼동하지 않습니다.
 * @property retryable 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property currentRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property observedAt 좌석 예약·대기열의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property expiresAt 좌석 예약·대기열의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property retryAfterSeconds 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class ApiError(
    /**
     * `code`은 예약 제어면에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: String,
    /**
     * `reason`은 예약 제어면에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reason: String,
    /**
     * `requestId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val requestId: String,
    /**
     * `retryable`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val retryable: Boolean,
    /**
     * `currentRevision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val currentRevision: Long?,
    /**
     * `observedAt`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val observedAt: Instant,
    /**
     * `expiresAt`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expiresAt: Instant? = null,
    /**
     * `retryAfterSeconds`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val retryAfterSeconds: Long? = null,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID = 1L
    }
}
