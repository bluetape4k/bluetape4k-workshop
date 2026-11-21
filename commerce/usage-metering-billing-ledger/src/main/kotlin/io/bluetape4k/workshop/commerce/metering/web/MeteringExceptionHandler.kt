package io.bluetape4k.workshop.commerce.metering.web

import io.bluetape4k.logging.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
/**
 * MeteringExceptionHandler은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class MeteringExceptionHandler {

    companion object: KLogging()

    /**
     * `invalid`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = invalid(ex = currentMeteringException)
     * ```
     * @param ex 요청 경계에서 검증된 `ex`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(ex: IllegalArgumentException): ResponseEntity<ApiError> {
        val message = ex.message ?: "invalid_request"
        val status = MeteringErrorStatus.from(ex)
        return ResponseEntity.status(status).body(ApiError(message, message))
    }

    /**
     * `state`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = state(ex = currentStateException)
     * ```
     * @param ex 요청 경계에서 검증된 `ex`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(IllegalStateException::class)
    fun state(ex: IllegalStateException): ResponseEntity<ApiError> {
        val message = ex.message ?: "invalid_state"
        val status = MeteringErrorStatus.from(ex)
        return ResponseEntity.status(status).body(ApiError(message, message))
    }
}

/**
 * MeteringErrorStatus은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal object MeteringErrorStatus {
    /**
     * from은 사용량 계량·청구의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun from(failure: RuntimeException): HttpStatus {
        val message = failure.message.orEmpty()
        return when (failure) {
            is IllegalArgumentException -> when {
                message.contains("conflict") ||
                        message.contains("not_open") ||
                        message.contains("stale") ->
                    HttpStatus.CONFLICT
                else                              ->
                    HttpStatus.BAD_REQUEST
            }
            is IllegalStateException    -> when {
                message.contains("not_ready") ||
                        message.contains("price_not_found") ->
                    HttpStatus.UNPROCESSABLE_CONTENT

                message.contains("not_found")               ->
                    HttpStatus.NOT_FOUND
                else                                        ->
                    HttpStatus.CONFLICT
            }
            else                        ->
                HttpStatus.INTERNAL_SERVER_ERROR
        }
    }
}
