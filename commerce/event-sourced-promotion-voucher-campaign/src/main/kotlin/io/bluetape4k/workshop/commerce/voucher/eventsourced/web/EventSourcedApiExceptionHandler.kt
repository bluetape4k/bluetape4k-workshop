package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.DatabaseBulkheadRejected
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
/**
 * EventSourcedApiError은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property code 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property reason 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class EventSourcedApiError(
    /**
     * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: String,
    /**
     * `reason`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reason: String,
)

/** database, token, digest, request payload 세부사항을 API client에 절대 serialize하지 않습니다. */
/** database, token, digest, request payload 세부사항을 API client에 절대 serialize하지 않습니다. */
@RestControllerAdvice
internal class EventSourcedApiExceptionHandler {

    private companion object: KLogging()
    /**
     * `eventStreamRejected`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = eventStreamRejected(failure = currentSseRejection)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(EventSourcedStreamRejected::class)
    fun eventStreamRejected(failure: EventSourcedStreamRejected): ResponseEntity<EventSourcedApiError> =
        ResponseEntity.status(failure.httpStatus)
            .body(EventSourcedApiError(failure.stableCode, failure.safeReason))

    /**
     * `databaseBusy`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예: `val response = databaseBusy()`처럼 검증된 입력 경계 뒤에서 호출합니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(DatabaseBulkheadRejected::class)
    fun databaseBusy(): ResponseEntity<EventSourcedApiError> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(
                EventSourcedApiError(
                    "DATABASE_BULKHEAD_REJECTED",
                    "database capacity is temporarily unavailable",
                ),
            )

    /**
     * `invalidInput`은 이벤트 소싱 command/query 경계의 입력 오류를 안정적인 400 응답으로 변환합니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 요청을 수정할 수 있도록 안전한 오류 코드만 반환합니다. 이벤트 stream이나
     * projection 상태를 오류
     * 응답에 섞어 외부로 노출하지 않습니다.
     * 예:
     * ```kotlin
     * val response = invalidInput(
     *     failure = IllegalArgumentException("campaignId is blank"),
     * )
     * ```
     * @param failure 요청 경계에서 발생한 검증 예외입니다.
     * @return 400 `INVALID_REQUEST` 오류를 반환합니다.
     */
    @ExceptionHandler(
        IllegalArgumentException::class,
        MissingRequestHeaderException::class,
        MethodArgumentNotValidException::class,
        MethodArgumentTypeMismatchException::class,
        HttpMessageNotReadableException::class,
    )
    /**
     * invalidInput은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * 예: event-sourced command 오류는 `400`과 안정적인 오류 코드를 반환하고 event stream을 변경하지 않습니다.
     */
    fun invalidInput(failure: Exception): ResponseEntity<EventSourcedApiError> {
        log.warn { "event_sourced_http_rejected category=INVALID_REQUEST failure=${failure.javaClass.simpleName}" }
        return ResponseEntity
            .badRequest()
            .body(
                EventSourcedApiError("INVALID_REQUEST", "request validation failed"),
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
     * val response = unexpected(failure = currentException)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(Exception::class)
    fun unexpected(failure: Exception): ResponseEntity<EventSourcedApiError> {
        log.warn { "event_sourced_http_failed category=INTERNAL_ERROR failure=${failure.javaClass.simpleName}" }
        return ResponseEntity
            .internalServerError()
            .body(
                EventSourcedApiError("INTERNAL_ERROR", "request could not be completed"),
            )
    }
}
