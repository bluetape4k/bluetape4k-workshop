package io.bluetape4k.workshop.exposed.mvc.vt.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.exposed.mvc.vt.order.exception.InsufficientStockException
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.io.Serializable
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

data class ErrorResponse(
    val status: Int,
    val code: String,
    val message: String,
) : Serializable {
    companion object {
        const val serialVersionUID = 1L
    }
}

@RestControllerAdvice
class GlobalExceptionHandler {

    companion object : KLogging()

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(e: NoSuchElementException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(404, "NOT_FOUND", e.message ?: "Resource not found"))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(400, "BAD_REQUEST", e.message ?: "Bad request"))
    }

    @ExceptionHandler(InsufficientStockException::class)
    fun handleInsufficientStock(e: InsufficientStockException): ResponseEntity<ErrorResponse> {
        log.warn { "Insufficient stock: product=${e.productId}" }
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse(409, "INSUFFICIENT_STOCK", "Insufficient stock for requested product"))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = e.bindingResult.fieldErrors
            .joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(400, "VALIDATION_ERROR", message))
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(e: ConstraintViolationException): ResponseEntity<ErrorResponse> {
        val message = e.constraintViolations.joinToString("; ") { it.message }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(400, "CONSTRAINT_VIOLATION", message))
    }

    /**
     * virtualFuture{}.get() wraps exceptions in ExecutionException / CompletionException.
     * Unwrap and re-dispatch to the appropriate handler.
     */
    @ExceptionHandler(ExecutionException::class, CompletionException::class)
    fun handleWrapped(e: Exception): ResponseEntity<ErrorResponse> {
        return when (val cause = e.cause ?: e) {
            is NoSuchElementException -> handleNotFound(cause)
            is InsufficientStockException -> handleInsufficientStock(cause)
            is IllegalArgumentException -> handleBadRequest(cause)
            else -> handleGeneral(cause as Exception)
        }
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneral(e: Exception): ResponseEntity<ErrorResponse> {
        log.warn(e) { "Unexpected error" }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(500, "INTERNAL_ERROR", "Internal server error"))
    }
}
