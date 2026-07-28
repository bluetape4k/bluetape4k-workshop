package io.bluetape4k.workshop.commerce.reservation.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCommandException
import io.bluetape4k.workshop.commerce.reservation.application.WaitlistCommandException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.io.Serializable
import java.time.Instant

/** credential을 누출하지 않고 안정적인 command rejection code를 retry-aware HTTP problem response로 매핑합니다. */
@RestControllerAdvice
internal class ApiExceptionHandler {
    @ExceptionHandler(ReservationCommandException::class)
    fun reservation(
        ex: ReservationCommandException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        val status =
            when (ex.reason) {
                "OWNER_MISMATCH" -> HttpStatus.FORBIDDEN
                "HOLD_EXPIRED" -> HttpStatus.GONE
                "ADMISSION_REJECTED" -> HttpStatus.TOO_MANY_REQUESTS
                else -> HttpStatus.CONFLICT
            }
        log.debug { "reservation_http_rejected reason=${ex.reason} status=${status.value()}" }
        val response = ResponseEntity.status(status)
        ex.retryAfterSeconds?.let { response.header("Retry-After", it.toString()) }
        return response.body(error(ex.reason, request, ex.retryable, ex.currentRevision, ex.retryAfterSeconds))
    }

    @ExceptionHandler(WaitlistCommandException::class)
    fun waitlist(
        ex: WaitlistCommandException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        val status =
            when (ex.reason) {
                "OWNER_MISMATCH" -> HttpStatus.FORBIDDEN
                "OFFER_EXPIRED" -> HttpStatus.GONE
                else -> HttpStatus.CONFLICT
            }
        log.debug { "reservation_http_rejected reason=${ex.reason} status=${status.value()}" }
        return ResponseEntity.status(status).body(error(ex.reason, request, false, ex.currentRevision))
    }

    @ExceptionHandler(IllegalArgumentException::class, MethodArgumentNotValidException::class)
    fun malformed(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        log.debug { "reservation_http_rejected reason=MALFORMED_REQUEST status=400" }
        return ResponseEntity.badRequest().body(error("MALFORMED_REQUEST", request, false, null))
    }

    private fun error(
        reason: String,
        request: HttpServletRequest,
        retryable: Boolean,
        revision: Long?,
        retryAfterSeconds: Long? = null,
    ) = ApiError(
        code = reason,
        reason = reason,
        requestId = request.getAttribute(RequestLoggingFilter.REQUEST_ID_ATTRIBUTE)?.toString() ?: request.requestId,
        retryable = retryable,
        currentRevision = revision,
        observedAt = Instant.now(),
        retryAfterSeconds = retryAfterSeconds
    )

    companion object : KLogging()
}

internal data class ApiError(
    val code: String,
    val reason: String,
    val requestId: String,
    val retryable: Boolean,
    val currentRevision: Long?,
    val observedAt: Instant,
    val expiresAt: Instant? = null,
    val retryAfterSeconds: Long? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
