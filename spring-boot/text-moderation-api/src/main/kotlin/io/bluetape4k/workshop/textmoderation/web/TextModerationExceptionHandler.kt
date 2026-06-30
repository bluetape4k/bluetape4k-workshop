package io.bluetape4k.workshop.textmoderation.web

import io.bluetape4k.workshop.textmoderation.model.ModerationErrorResponse
import io.bluetape4k.workshop.textmoderation.service.PayloadTooLargeException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps moderation API failures to stable workshop HTTP responses.
 */
@RestControllerAdvice
class TextModerationExceptionHandler {

    @ExceptionHandler(PayloadTooLargeException::class)
    fun handlePayloadTooLarge(exception: PayloadTooLargeException): ResponseEntity<ModerationErrorResponse> =
        error(
            status = 413,
            error = "Content Too Large",
            message = exception.message ?: "text payload is too large",
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleValidation(exception: IllegalArgumentException): ResponseEntity<ModerationErrorResponse> =
        error(HttpStatus.BAD_REQUEST, exception.message ?: "invalid moderation request")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMalformedBody(exception: HttpMessageNotReadableException): ResponseEntity<ModerationErrorResponse> =
        error(HttpStatus.BAD_REQUEST, exception.message ?: "request body is not readable")

    private fun error(status: HttpStatus, message: String): ResponseEntity<ModerationErrorResponse> =
        error(
            status = status.value(),
            error = status.reasonPhrase,
            message = message,
        )

    private fun error(status: Int, error: String, message: String): ResponseEntity<ModerationErrorResponse> =
        ResponseEntity
            .status(status)
            .body(
                ModerationErrorResponse(
                    status = status,
                    error = error,
                    message = message,
                ),
            )
}
