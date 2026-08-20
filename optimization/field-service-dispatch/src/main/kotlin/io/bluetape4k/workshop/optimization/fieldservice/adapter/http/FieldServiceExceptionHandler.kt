package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceConflict
import io.bluetape4k.workshop.optimization.fieldservice.domain.InvalidFieldServiceInput
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
internal class FieldServiceExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException::class, InvalidFieldServiceInput::class, IllegalArgumentException::class)
    fun badRequest(): ResponseEntity<FieldServiceErrorResponse> = error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "invalid request")

    @ExceptionHandler(FieldServiceBodyTooLargeException::class)
    fun bodyTooLarge(): ResponseEntity<FieldServiceErrorResponse> =
        error(HttpStatus.valueOf(413), "BODY_TOO_LARGE", "request body is too large")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(exception: HttpMessageNotReadableException): ResponseEntity<FieldServiceErrorResponse> =
        if (exception.causes().any { it is FieldServiceBodyTooLargeException }) {
            bodyTooLarge()
        } else {
            badRequest()
        }

    @ExceptionHandler(FieldServiceConflict::class)
    fun conflict(exception: FieldServiceConflict): ResponseEntity<FieldServiceErrorResponse> =
        error(HttpStatus.CONFLICT, exception.code.name, "field service conflict")

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(): ResponseEntity<FieldServiceErrorResponse> = error(HttpStatus.NOT_FOUND, "NOT_FOUND", "resource not found")

    private fun error(status: HttpStatus, code: String, message: String): ResponseEntity<FieldServiceErrorResponse> =
        ResponseEntity.status(status).body(FieldServiceErrorResponse(status.value(), code, message))

    private fun Throwable.causes(): Sequence<Throwable> =
        generateSequence(this) { it.cause }
}
