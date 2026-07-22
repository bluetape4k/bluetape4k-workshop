package io.bluetape4k.workshop.commerce.metering.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class MeteringExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(ex: IllegalArgumentException): ResponseEntity<ApiError> {
        val message = ex.message ?: "invalid_request"
        val status = MeteringErrorStatus.from(ex)
        return ResponseEntity.status(status).body(ApiError(message, message))
    }

    @ExceptionHandler(IllegalStateException::class)
    fun state(ex: IllegalStateException): ResponseEntity<ApiError> {
        val message = ex.message ?: "invalid_state"
        val status = MeteringErrorStatus.from(ex)
        return ResponseEntity.status(status).body(ApiError(message, message))
    }
}

internal object MeteringErrorStatus {
    fun from(failure: RuntimeException): HttpStatus {
        val message = failure.message.orEmpty()
        return when (failure) {
            is IllegalArgumentException -> when {
                message.contains("conflict") || message.contains("not_open") || message.contains("stale") ->
                    HttpStatus.CONFLICT
                else -> HttpStatus.BAD_REQUEST
            }
            is IllegalStateException -> when {
                message.contains("not_found") -> HttpStatus.NOT_FOUND
                message.contains("not_ready") || message.contains("price_not_found") ->
                    HttpStatus.UNPROCESSABLE_CONTENT
                else -> HttpStatus.CONFLICT
            }
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }
    }
}
