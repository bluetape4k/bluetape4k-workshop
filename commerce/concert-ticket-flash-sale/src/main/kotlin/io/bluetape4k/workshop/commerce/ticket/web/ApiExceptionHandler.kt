package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.workshop.commerce.ticket.persistence.TicketDatabasePermitUnavailable
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.ActivePurchaseExists
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.InventoryUnavailable
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.PurchaseLimitExceeded
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.PurchaseNotFound
import io.bluetape4k.workshop.commerce.ticket.redis.AdmissionTemporarilyUnavailable
import io.bluetape4k.workshop.commerce.ticket.redis.PurchaseApprovalInProgress
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.io.Serial
import java.io.Serializable
import java.time.Instant
import io.bluetape4k.idgenerators.uuid.Uuid

data class TicketProblem(
    val code: String,
    val status: Int,
    val retryable: Boolean,
    val retryAt: Instant? = null,
    val nextAction: String? = null,
    val correlationId: String,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Stable allowlist problem responses; exception messages and raw input never cross the HTTP boundary. */
@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(PurchaseNotFound::class)
    fun notFound(request: HttpServletRequest) = problem(request, HttpStatus.NOT_FOUND, "purchase_not_found", false)

    @ExceptionHandler(AdmissionTemporarilyUnavailable::class, TicketDatabasePermitUnavailable::class)
    fun unavailable(request: HttpServletRequest) =
        problem(request, HttpStatus.SERVICE_UNAVAILABLE, "admission_temporarily_unavailable", true, "retry")

    @ExceptionHandler(PurchaseApprovalInProgress::class, ActivePurchaseExists::class)
    fun busy(request: HttpServletRequest) =
        problem(request, HttpStatus.CONFLICT, "purchase_approval_in_progress", true, "wait_for_current_attempt")

    @ExceptionHandler(InventoryUnavailable::class)
    fun soldOut(request: HttpServletRequest) = problem(request, HttpStatus.CONFLICT, "inventory_unavailable", false)

    @ExceptionHandler(PurchaseLimitExceeded::class)
    fun limit(request: HttpServletRequest) = problem(request, HttpStatus.CONFLICT, "purchase_limit_exceeded", false)

    @ExceptionHandler(MethodArgumentNotValidException::class, IllegalArgumentException::class)
    fun invalid(request: HttpServletRequest) = problem(request, HttpStatus.BAD_REQUEST, "invalid_request", false)

    private fun problem(
        request: HttpServletRequest,
        status: HttpStatus,
        code: String,
        retryable: Boolean,
        nextAction: String? = null,
    ): ResponseEntity<TicketProblem> = ResponseEntity.status(status).body(
        TicketProblem(code, status.value(), retryable, nextAction = nextAction, correlationId = correlationId(request)),
    )

    private fun correlationId(request: HttpServletRequest): String =
        request.getHeader("X-Correlation-Id")
            ?.takeIf { it.length in 8..64 && it.all(CORRELATION_CHARACTERS::contains) }
            ?: Uuid.V7.nextId().toString()

    companion object {
        private val CORRELATION_CHARACTERS = (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '_')).toSet()
    }
}
