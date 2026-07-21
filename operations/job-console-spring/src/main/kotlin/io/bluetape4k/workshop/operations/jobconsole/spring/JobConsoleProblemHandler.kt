package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.workshop.operations.jobconsole.api.JobProblem
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

@RestControllerAdvice
class JobConsoleProblemHandler {
    @ExceptionHandler(JobRepositoryException::class)
    fun repository(failure: JobRepositoryException): ResponseEntity<JobProblem> {
        val status =
            when (failure.code) {
                JobProblemCode.JOB_NOT_FOUND -> HttpStatus.NOT_FOUND
                JobProblemCode.SCOPE_DENIED -> HttpStatus.FORBIDDEN
                JobProblemCode.IDEMPOTENCY_KEY_REUSED -> HttpStatus.CONFLICT
                else -> HttpStatus.CONFLICT
            }
        return ResponseEntity.status(status).body(problem(status, failure.code))
    }

    @ExceptionHandler(IllegalArgumentException::class, MethodArgumentNotValidException::class)
    fun validation(): ResponseEntity<JobProblem> =
        ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, JobProblemCode.VALIDATION_FAILED))

    private fun problem(status: HttpStatus, code: JobProblemCode): JobProblem =
        JobProblem(status.value(), code, status.reasonPhrase, UUID.randomUUID().toString())
}
