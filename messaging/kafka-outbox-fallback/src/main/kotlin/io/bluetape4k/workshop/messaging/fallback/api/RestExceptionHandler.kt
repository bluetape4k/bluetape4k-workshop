package io.bluetape4k.workshop.messaging.fallback.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.io.Serializable

/**
 * Sanitizes validation errors for workshop REST endpoints.
 */
@RestControllerAdvice
class RestExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationError(ex: MethodArgumentNotValidException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                error = "INVALID_REQUEST",
                message = "Request validation failed",
                fieldCount = ex.bindingResult.fieldErrorCount,
            ),
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                error = "INVALID_REQUEST",
                message = "Request validation failed",
                fieldCount = 0,
            ),
        )
}

/**
 * Safe error response that does not echo rejected user input.
 */
data class ApiErrorResponse(
    val error: String,
    val message: String,
    val fieldCount: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
