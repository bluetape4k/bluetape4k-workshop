package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.workshop.operations.jobconsole.application.JobSubmissionHttpMapper
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
/**
 * JobConsoleProblemHandler은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class JobConsoleProblemHandler {
    /**
     * `repository`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = repository(JobRepositoryException(JobProblemCode.DEPENDENCY_UNAVAILABLE))
     * // 응답 status는 503이며 내부 stack trace나 SQL 세부 정보는 노출하지 않는다.
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(JobRepositoryException::class)
    fun repository(failure: JobRepositoryException): ResponseEntity<ByteArray> {
        val status =
            when (failure.code) {
                JobProblemCode.JOB_NOT_FOUND -> HttpStatus.NOT_FOUND
                JobProblemCode.SCOPE_DENIED -> HttpStatus.FORBIDDEN
                JobProblemCode.IDEMPOTENCY_KEY_REUSED -> HttpStatus.CONFLICT
                JobProblemCode.IDEMPOTENCY_IN_FLIGHT -> HttpStatus.CONFLICT
                JobProblemCode.IDEMPOTENCY_WAITERS_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS
                JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED -> HttpStatus.INTERNAL_SERVER_ERROR
                JobProblemCode.DEPENDENCY_UNAVAILABLE,
                JobProblemCode.LEASE_LOST,
                -> HttpStatus.SERVICE_UNAVAILABLE
                else -> HttpStatus.CONFLICT
            }
        return response(JobSubmissionHttpMapper.problem(failure.code, status.value(), status.reasonPhrase))
    }

    /**
     * `scopeDenied`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예: `val response = scopeDenied()`처럼 검증된 입력 경계 뒤에서 호출합니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(JobSubmissionScopeDeniedException::class)
    fun scopeDenied(): ResponseEntity<ByteArray> =
        response(JobSubmissionHttpMapper.problem(JobProblemCode.SCOPE_DENIED, HttpStatus.FORBIDDEN.value(), HttpStatus.FORBIDDEN.reasonPhrase))

    /**
     * `tooLarge`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예: `val response = tooLarge()`처럼 검증된 입력 경계 뒤에서 호출합니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(JobSubmissionRequestTooLargeException::class)
    fun tooLarge(): ResponseEntity<ByteArray> =
        response(
            JobSubmissionHttpMapper.problem(
                JobProblemCode.IDEMPOTENCY_REQUEST_TOO_LARGE,
                HttpStatus.valueOf(413).value(),
                HttpStatus.valueOf(413).reasonPhrase,
            ),
        )

    /**
     * `invalidSubmission`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예: `val response = invalidSubmission()`처럼 검증된 입력 경계 뒤에서 호출합니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(JobSubmissionInvalidRequestException::class)
    fun invalidSubmission(): ResponseEntity<ByteArray> =
        response(JobSubmissionHttpMapper.problem(JobProblemCode.INVALID_IDEMPOTENCY_REQUEST, HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.reasonPhrase))

    /**
     * `validation`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예: `val response = validation()`처럼 검증된 입력 경계 뒤에서 호출합니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(IllegalArgumentException::class, MethodArgumentNotValidException::class)
    fun validation(): ResponseEntity<ByteArray> =
        response(JobSubmissionHttpMapper.problem(JobProblemCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.reasonPhrase))

    /**
     * response은 운영 작업 콘솔의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * 예: `202` submission 응답은 jobId와 현재 snapshot만 반환하고 tenant·payload·stack trace를 노출하지 않습니다.
     */
    private fun response(result: io.bluetape4k.workshop.operations.jobconsole.api.JobSubmissionHttpResponse): ResponseEntity<ByteArray> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.parseMediaType(result.contentType)
        result.headers.forEach { (name, values) -> values.forEach { headers.add(name, it) } }
        return ResponseEntity.status(result.status).headers(headers).body(result.body)
    }
}
