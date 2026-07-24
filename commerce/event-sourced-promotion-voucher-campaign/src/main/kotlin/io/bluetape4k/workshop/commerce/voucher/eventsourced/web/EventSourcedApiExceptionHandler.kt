package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.DatabaseBulkheadRejected
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

internal data class EventSourcedApiError(
    val code: String,
    val reason: String,
)

/** Never serializes database, token, digest, or request payload details to API clients. */
@RestControllerAdvice
internal class EventSourcedApiExceptionHandler {
    @ExceptionHandler(EventSourcedStreamRejected::class)
    fun eventStreamRejected(failure: EventSourcedStreamRejected): ResponseEntity<EventSourcedApiError> =
        ResponseEntity.status(failure.httpStatus)
            .body(EventSourcedApiError(failure.stableCode, failure.safeReason))

    @ExceptionHandler(DatabaseBulkheadRejected::class)
    fun databaseBusy(): ResponseEntity<EventSourcedApiError> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(
                EventSourcedApiError(
                    "DATABASE_BULKHEAD_REJECTED",
                    "database capacity is temporarily unavailable",
                ),
            )

    @ExceptionHandler(
        IllegalArgumentException::class,
        MissingRequestHeaderException::class,
        MethodArgumentNotValidException::class,
        MethodArgumentTypeMismatchException::class,
        HttpMessageNotReadableException::class,
    )
    fun invalidInput(failure: Exception): ResponseEntity<EventSourcedApiError> {
        log.warn { "event_sourced_http_rejected category=INVALID_REQUEST failure=${failure.javaClass.simpleName}" }
        return ResponseEntity.badRequest().body(
            EventSourcedApiError("INVALID_REQUEST", "request validation failed"),
        )
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(failure: Exception): ResponseEntity<EventSourcedApiError> {
        log.warn { "event_sourced_http_failed category=INTERNAL_ERROR failure=${failure.javaClass.simpleName}" }
        return ResponseEntity.internalServerError().body(
            EventSourcedApiError("INTERNAL_ERROR", "request could not be completed"),
        )
    }

    private companion object : KLogging()
}
