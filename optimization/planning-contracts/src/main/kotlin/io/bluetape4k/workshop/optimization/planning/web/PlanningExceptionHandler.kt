package io.bluetape4k.workshop.optimization.planning.web

import io.bluetape4k.workshop.optimization.planning.application.InvalidCallbackSignatureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
internal class PlanningExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException::class, IllegalArgumentException::class)
    fun badRequest(): ResponseEntity<ErrorResponse> = error(HttpStatus.BAD_REQUEST, "invalid request")

    @ExceptionHandler(InvalidCallbackSignatureException::class)
    fun invalidSignature(): ResponseEntity<ErrorResponse> = error(HttpStatus.UNAUTHORIZED, "invalid callback signature")

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(): ResponseEntity<ErrorResponse> = error(HttpStatus.NOT_FOUND, "planning request not found")

    @ExceptionHandler(IllegalStateException::class)
    fun conflict(): ResponseEntity<ErrorResponse> = error(HttpStatus.CONFLICT, "planning state conflict")

    private fun error(status: HttpStatus, message: String): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(
            ErrorResponse(
                status = status.value(),
                error = status.reasonPhrase,
                message = message,
            ),
        )
}
