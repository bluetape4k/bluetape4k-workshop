package io.bluetape4k.workshop.commerce.order.web

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.io.Serializable
/**
 * ApiError은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property code 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property correlationId 요청 추적 식별자입니다. API 오류·로그·이벤트를 하나의 업무 시도로 연결하되 비즈니스 식별자와 혼동하지 않습니다.
 */


internal data class ApiError(
    /**
     * `code`은 주문·결제·재고·배송에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: String,
    /**
     * `correlationId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val correlationId: String,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

@RestControllerAdvice
/**
 * ApiExceptionHandler은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class ApiExceptionHandler {

    companion object: KLogging()

    /**
     * `badRequest`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = badRequest(failure = currentBadRequest)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(failure: IllegalArgumentException): ResponseEntity<ApiError> =
        error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", failure)

    /**
     * `capacity`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = capacity(failure = currentCapacityRejection)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(StreamCapacityExceeded::class)
    fun capacity(failure: StreamCapacityExceeded): ResponseEntity<ApiError> =
        error(HttpStatus.SERVICE_UNAVAILABLE, "SSE_CAPACITY_EXCEEDED", failure)

    /**
     * `streamShuttingDown`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = streamShuttingDown(failure = currentShutdownFailure)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(StreamShuttingDown::class)
    fun streamShuttingDown(failure: StreamShuttingDown): ResponseEntity<ApiError> =
        error(HttpStatus.SERVICE_UNAVAILABLE, "SSE_SHUTTING_DOWN", failure)

    /**
     * `notFound`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = notFound(failure = currentNotFound)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(failure: NoSuchElementException): ResponseEntity<ApiError> =
        error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", failure)

    /**
     * `conflict`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = conflict(failure = currentConflict)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(IllegalStateException::class)
    fun conflict(failure: IllegalStateException): ResponseEntity<ApiError> =
        error(HttpStatus.CONFLICT, "STATE_CONFLICT", failure)

    /**
     * error은 주문 라이프사이클과 fulfillment의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * 예: 저장소·provider 오류는 내부 stack trace 대신 추적 가능한 error code와 HTTP 상태로 변환합니다.
     */
    private fun error(
        status: HttpStatus,
        code: String,
        failure: Exception,
    ): ResponseEntity<ApiError> {
        val correlationId = Uuid.V7.nextId().toString()
        log.warn(failure) { "http_request_failed status=${status.value()} code=$code correlationId=$correlationId" }
        return ResponseEntity.status(status).body(ApiError(code, correlationId))
    }
}
