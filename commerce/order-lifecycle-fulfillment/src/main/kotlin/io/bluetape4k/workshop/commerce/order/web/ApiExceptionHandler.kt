package io.bluetape4k.workshop.commerce.order.web

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.io.Serializable

internal data class ApiError(
    val code: String,
    val correlationId: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@RestControllerAdvice
internal class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(failure: IllegalArgumentException): ResponseEntity<ApiError> =
        error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", failure)

    @ExceptionHandler(StreamCapacityExceeded::class)
    fun capacity(failure: StreamCapacityExceeded): ResponseEntity<ApiError> =
        error(HttpStatus.SERVICE_UNAVAILABLE, "SSE_CAPACITY_EXCEEDED", failure)

    @ExceptionHandler(StreamShuttingDown::class)
    fun streamShuttingDown(failure: StreamShuttingDown): ResponseEntity<ApiError> =
        error(HttpStatus.SERVICE_UNAVAILABLE, "SSE_SHUTTING_DOWN", failure)

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(failure: NoSuchElementException): ResponseEntity<ApiError> =
        error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", failure)

    @ExceptionHandler(IllegalStateException::class)
    fun conflict(failure: IllegalStateException): ResponseEntity<ApiError> =
        error(HttpStatus.CONFLICT, "STATE_CONFLICT", failure)

    private fun error(
        status: HttpStatus,
        code: String,
        failure: Exception,
    ): ResponseEntity<ApiError> {
        val correlationId = Uuid.V7.nextId().toString()
        log.warn(failure) { "http_request_failed status=${status.value()} code=$code correlationId=$correlationId" }
        return ResponseEntity.status(status).body(ApiError(code, correlationId))
    }

    companion object : KLogging()
}
