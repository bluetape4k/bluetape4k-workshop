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
        ResponseEntity.status(exception.status).body(ShiftCoverageErrorResponse(exception.code, requestId(request), exception.retryable))

    @ExceptionHandler(ShiftCoverageConflict::class)
    fun handleConflict(exception: ShiftCoverageConflict, request: HttpServletRequest): ResponseEntity<ShiftCoverageErrorResponse> =
        ResponseEntity.status(409).body(ShiftCoverageErrorResponse(exception.code.name, requestId(request), false))

    @ExceptionHandler(ShiftCoverageOutboxRedriveRejected::class)
    fun handleOutboxRedrive(exception: ShiftCoverageOutboxRedriveRejected, request: HttpServletRequest): ResponseEntity<ShiftCoverageErrorResponse> =
        ResponseEntity.status(409).body(ShiftCoverageErrorResponse("OUTBOX_REDRIVE_REJECTED", requestId(request), false))

    @ExceptionHandler(ShiftCoverageInboxRequeueRejected::class)
    fun handleInboxRequeue(exception: ShiftCoverageInboxRequeueRejected, request: HttpServletRequest): ResponseEntity<ShiftCoverageErrorResponse> =
        ResponseEntity.status(409).body(ShiftCoverageErrorResponse("INBOX_REQUEUE_REJECTED", requestId(request), false))

    @ExceptionHandler(InvalidShiftCoverageInput::class, IllegalArgumentException::class)
    fun handleInput(exception: RuntimeException, request: HttpServletRequest): ResponseEntity<ShiftCoverageErrorResponse> =
        ResponseEntity.badRequest().body(ShiftCoverageErrorResponse("REQUEST_INVALID", requestId(request), false))

    @ExceptionHandler(
        MissingRequestHeaderException::class,
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
    )
    fun handleMalformedHttp(exception: Exception, request: HttpServletRequest): ResponseEntity<ShiftCoverageErrorResponse> =
        ResponseEntity.badRequest().body(ShiftCoverageErrorResponse("REQUEST_INVALID", requestId(request), false))

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleTooLarge(exception: MaxUploadSizeExceededException, request: HttpServletRequest): ResponseEntity<ShiftCoverageErrorResponse> =
        ResponseEntity.status(HttpStatus.valueOf(413))
            .body(ShiftCoverageErrorResponse("RESPONSE_TOO_LARGE", requestId(request), false))

    private fun requestId(request: HttpServletRequest): String = request.getHeader("X-Request-Id")?.takeIf { it.isNotBlank() } ?: Uuid.V7.nextId().toString()
}
