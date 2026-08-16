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
class JobConsoleProblemHandler {
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

    @ExceptionHandler(JobSubmissionScopeDeniedException::class)
    fun scopeDenied(): ResponseEntity<ByteArray> =
        response(JobSubmissionHttpMapper.problem(JobProblemCode.SCOPE_DENIED, HttpStatus.FORBIDDEN.value(), HttpStatus.FORBIDDEN.reasonPhrase))

    @ExceptionHandler(JobSubmissionRequestTooLargeException::class)
    fun tooLarge(): ResponseEntity<ByteArray> =
        response(
            JobSubmissionHttpMapper.problem(
                JobProblemCode.IDEMPOTENCY_REQUEST_TOO_LARGE,
                HttpStatus.valueOf(413).value(),
                HttpStatus.valueOf(413).reasonPhrase,
            ),
        )

    @ExceptionHandler(JobSubmissionInvalidRequestException::class)
    fun invalidSubmission(): ResponseEntity<ByteArray> =
        response(JobSubmissionHttpMapper.problem(JobProblemCode.INVALID_IDEMPOTENCY_REQUEST, HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.reasonPhrase))

    @ExceptionHandler(IllegalArgumentException::class, MethodArgumentNotValidException::class)
    fun validation(): ResponseEntity<ByteArray> =
        response(JobSubmissionHttpMapper.problem(JobProblemCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.reasonPhrase))

    private fun response(result: io.bluetape4k.workshop.operations.jobconsole.api.JobSubmissionHttpResponse): ResponseEntity<ByteArray> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.parseMediaType(result.contentType)
        result.headers.forEach { (name, values) -> values.forEach { headers.add(name, it) } }
        return ResponseEntity.status(result.status).headers(headers).body(result.body)
    }
}
