package io.bluetape4k.workshop.optimization.shiftcoverage.web

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageInboxRequeueRejected
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageOutboxRedriveRejected
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageConflict
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.InvalidShiftCoverageInput
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

/** stable error code와 request id만 외부에 노출하며 internal exception은 redacted합니다. */
@RestControllerAdvice
class ShiftCoverageExceptionHandler {
    @ExceptionHandler(ShiftCoverageHttpException::class)
    fun handle(exception: ShiftCoverageHttpException, request: HttpServletRequest): ResponseEntity<ShiftCoverageErrorResponse> =
        error(exception.status, exception.code, request, exception.retryable)

    @ExceptionHandler(ShiftCoverageConflict::class)
    fun handleConflict(exception: ShiftCoverageConflict, request: HttpServletRequest): ResponseEntity<ShiftCoverageErrorResponse> =
        error(HttpStatus.CONFLICT, exception.code.name, request, false)

    @ExceptionHandler(ShiftCoverageOutboxRedriveRejected::class)
    fun handleOutboxRedrive(exception: ShiftCoverageOutboxRedriveRejected, request: HttpServletRequest): ResponseEntity<ShiftCoverageErrorResponse> =
        error(HttpStatus.CONFLICT, "OUTBOX_REDRIVE_REJECTED", request, false)

    @ExceptionHandler(ShiftCoverageInboxRequeueRejected::class)
    fun handleInboxRequeue(exception: ShiftCoverageInboxRequeueRejected, request: HttpServletRequest): ResponseEntity<ShiftCoverageErrorResponse> =
        error(HttpStatus.CONFLICT, "INBOX_REQUEUE_REJECTED", request, false)

    @ExceptionHandler(InvalidShiftCoverageInput::class, IllegalArgumentException::class)
    fun handleInput(exception: RuntimeException, request: HttpServletRequest): ResponseEntity<ShiftCoverageErrorResponse> =
        error(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", request, false)

    @ExceptionHandler(
        MissingRequestHeaderException::class,
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
    )
    fun handleMalformedHttp(exception: Exception, request: HttpServletRequest): ResponseEntity<ShiftCoverageErrorResponse> =
        error(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", request, false)

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleTooLarge(exception: MaxUploadSizeExceededException, request: HttpServletRequest): ResponseEntity<ShiftCoverageErrorResponse> =
        error(HttpStatus.valueOf(413), "RESPONSE_TOO_LARGE", request, false)

    private fun error(
        status: HttpStatus,
        code: String,
        request: HttpServletRequest,
        retryable: Boolean,
    ): ResponseEntity<ShiftCoverageErrorResponse> {
        val contract = ERROR_CONTRACTS[code] ?: ErrorContract()
        val retryAfter = contract.retryAfter?.takeIf { retryable && status == HttpStatus.TOO_MANY_REQUESTS }
        val body = ShiftCoverageErrorResponse(
            code = code,
            requestId = requestId(request),
            retryable = retryable,
            retryAfter = retryAfter,
            nextAction = contract.nextAction,
        )
        val response = ResponseEntity.status(status)
        retryAfter?.let { response.header("Retry-After", it.toString()) }
        return response.body(body)
    }

    private data class ErrorContract(
        val nextAction: String? = null,
        val retryAfter: Long? = null,
    )

    companion object {
        private val ERROR_CONTRACTS = mapOf(
            "REQUEST_INVALID" to ErrorContract(nextAction = "FIX_REQUEST"),
            "CALLBACK_SIGNATURE_INVALID" to ErrorContract(nextAction = "FIX_SIGNATURE"),
            "DEMO_ROLE_FORBIDDEN" to ErrorContract(nextAction = "USE_ALLOWED_ROLE"),
            "LOOPBACK_REQUIRED" to ErrorContract(nextAction = "USE_LOOPBACK"),
            "ORIGIN_FORBIDDEN" to ErrorContract(nextAction = "USE_SAME_ORIGIN"),
            "REVISION_CONFLICT" to ErrorContract(nextAction = "REFRESH_PLAN"),
            "IDEMPOTENCY_KEY_REUSED" to ErrorContract(nextAction = "USE_NEW_KEY"),
            "CALLBACK_REPLAY" to ErrorContract(nextAction = "DROP_EVENT"),
            "EVENT_KEY_REUSED" to ErrorContract(nextAction = "DROP_EVENT"),
            "STALE" to ErrorContract(nextAction = "REFRESH_PLAN"),
            "RESPONSE_TOO_LARGE" to ErrorContract(nextAction = "SHRINK_INPUT"),
            "RETRY_EXHAUSTED" to ErrorContract(nextAction = "OPERATOR_REQUEUE"),
            "REPLAN_REJECTED" to ErrorContract(nextAction = "RETRY_AFTER", retryAfter = 1L),
            "DEMO_PROFILE_REQUIRED" to ErrorContract(nextAction = "ENABLE_DEMO"),
            "OUTBOX_REDRIVE_REJECTED" to ErrorContract(nextAction = "REVIEW_OUTBOX"),
            "INBOX_REQUEUE_REJECTED" to ErrorContract(nextAction = "REVIEW_INBOX"),
        )
    }

    private fun requestId(request: HttpServletRequest): String = request.getHeader("X-Request-Id")?.takeIf { it.isNotBlank() } ?: Uuid.V7.nextId().toString()
}
