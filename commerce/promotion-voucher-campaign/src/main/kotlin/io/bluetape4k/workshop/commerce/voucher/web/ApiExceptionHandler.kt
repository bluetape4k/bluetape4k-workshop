package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitRejected
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import kotlin.math.ceil

@RestControllerAdvice
internal class ApiExceptionHandler {
    @ExceptionHandler(VoucherApiException::class)
    fun voucherFailure(
        failure: VoucherApiException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> =
        errorResponse(
            failure.status,
            failure.stableCode,
            failure.safeReason,
            request.requestId(),
            failure.retryAfterSeconds,
        )

    @ExceptionHandler(DatabasePermitRejected::class)
    fun databaseBusy(
        failure: DatabasePermitRejected,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> =
        errorResponse(
            503,
            "DATABASE_BULKHEAD_REJECTED",
            "database capacity is temporarily unavailable",
            request.requestId(),
            ceil(failure.retryAfter.toMillis() / 1_000.0).toLong().coerceAtLeast(1),
        )

    @ExceptionHandler(SseCapacityRejected::class)
    fun sseCapacity(
        failure: SseCapacityRejected,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        val fallback = "/api/v1/campaigns/${failure.campaignId}"
        val headers = HttpHeaders()
        headers.set("Retry-After", "2")
        headers.set(HttpHeaders.LINK, "<$fallback>; rel=\"alternate\"; type=\"application/json\"")
        return ResponseEntity.status(503).headers(headers).body(
            ApiError(
                "SSE_CAPACITY_REJECTED",
                "event stream capacity is temporarily unavailable",
                request.requestId(),
                2,
            ),
        )
    }

    @ExceptionHandler(VoucherServiceShuttingDown::class)
    fun shuttingDown(
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> =
        errorResponse(503, "SERVICE_SHUTTING_DOWN", "service is shutting down", request.requestId(), 1)

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
        MissingRequestHeaderException::class,
        MethodArgumentTypeMismatchException::class,
        IllegalArgumentException::class,
    )
    fun invalidInput(
        failure: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        log.warn { "voucher_http_rejected category=INVALID_REQUEST failure=${failure.javaClass.simpleName}" }
        return errorResponse(400, "INVALID_REQUEST", "request validation failed", request.requestId(), null)
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun missingResource(
        failure: NoResourceFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        log.warn { "voucher_http_rejected category=NOT_FOUND failure=${failure.javaClass.simpleName}" }
        return errorResponse(404, "RESOURCE_NOT_FOUND", "resource was not found", request.requestId(), null)
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(
        failure: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        log.warn(failure) { "voucher_http_failed category=INTERNAL_ERROR failure=${failure.javaClass.simpleName}" }
        return errorResponse(500, "INTERNAL_ERROR", "request could not be completed", request.requestId(), null)
    }

    companion object : KLogging()
}

internal fun executedResponse(
    executed: ExecutedHttpCommand,
    request: HttpServletRequest,
    successBody: () -> Any,
): ResponseEntity<Any> {
    val stored = executed.response
    val headers = HttpHeaders()
    stored.headers
        .filterKeys { !it.isStoredDescriptorHeader() }
        .forEach(headers::set)
    headers.set("Idempotency-Replayed", executed.replayed.toString())
    val body =
        if (stored.status >= 400) {
            ApiError(
                code = stored.responseKind.name,
                reason = stored.responseKind.safeReason(),
                requestId = request.requestId(),
                retryAfterSeconds = stored.headers["Retry-After"]?.toLongOrNull(),
            )
        } else {
            successBody()
        }
    return ResponseEntity.status(stored.status).headers(headers).body(body)
}

private fun io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherResponseKind.safeReason(): String =
    name.lowercase().replace('_', ' ')

internal fun errorResponse(
    status: Int,
    code: String,
    reason: String,
    requestId: String,
    retryAfterSeconds: Long?,
): ResponseEntity<ApiError> {
    val headers = HttpHeaders()
    retryAfterSeconds?.let { headers.set("Retry-After", it.toString()) }
    return ResponseEntity.status(status).headers(headers).body(ApiError(code, reason, requestId, retryAfterSeconds))
}

internal fun HttpServletRequest.requestId(): String =
    getAttribute(REQUEST_ID_ATTRIBUTE) as? String ?: newRequestId()
