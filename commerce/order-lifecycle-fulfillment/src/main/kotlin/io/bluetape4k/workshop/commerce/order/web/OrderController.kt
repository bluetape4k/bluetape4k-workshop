package io.bluetape4k.workshop.commerce.order.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.order.application.IdempotentOrderSubmissionService
import io.bluetape4k.workshop.commerce.order.application.OrderCommandService
import io.bluetape4k.workshop.commerce.order.application.PaymentEventService
import io.bluetape4k.workshop.commerce.order.application.PublicationReconciliationService
import io.bluetape4k.workshop.commerce.order.application.SubmitOrderRequest
import io.bluetape4k.workshop.commerce.order.domain.FulfillmentStatus
import io.bluetape4k.workshop.commerce.order.query.OrderLifecycleQueryService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.Serializable
import java.util.UUID

internal data class AdvanceFulfillmentRequest(
    val target: FulfillmentStatus,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class CancelLineRequest(
    val quantity: Int,
    val reasonCode: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@RestController
@RequestMapping("/api/v1")
internal class OrderController(
    private val submissions: IdempotentOrderSubmissionService,
    private val commands: OrderCommandService,
    private val queries: OrderLifecycleQueryService,
    private val streams: OrderEventStream,
    private val reconciliation: PublicationReconciliationService,
    private val paymentEvents: PaymentEventService,
) {
    @PostMapping(
        "/orders",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun submit(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: SubmitOrderRequest,
    ): ResponseEntity<String> {
        val result = submissions.submit(idempotencyKey, request)
        log.debug {
            "http_order_submit status=${result.status.value()} replayed=${result.replayed} " +
                "providerMode=${request.providerMode} lineCount=${request.lines.size}"
        }
        val response =
            ResponseEntity
                .status(result.status)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Replayed", result.replayed.toString())
        result.retryAfterSeconds?.let { response.header(HttpHeaders.RETRY_AFTER, it.toString()) }
        return response.body(result.body)
    }

    @GetMapping("/orders/{orderId}")
    fun snapshot(
        @PathVariable orderId: UUID,
    ) = queries.snapshot(orderId)

    @PostMapping("/orders/{orderId}/fulfillments/{groupId}/advance")
    fun advance(
        @PathVariable orderId: UUID,
        @PathVariable groupId: UUID,
        @RequestBody request: AdvanceFulfillmentRequest,
    ): ResponseEntity<Void> {
        val snapshot = queries.snapshot(orderId)
        check(snapshot.fulfillments.any { it.id == groupId }) { "fulfillment group does not belong to order" }
        return if (commands.advanceFulfillment(groupId, request.target)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.status(409).build()
        }
    }

    @PostMapping("/orders/{orderId}/lines/{lineId}/cancel")
    fun cancelLine(
        @PathVariable orderId: UUID,
        @PathVariable lineId: UUID,
        @RequestBody request: CancelLineRequest,
    ) = commands.cancelUnshippedLine(orderId, lineId, request.quantity, request.reasonCode)

    @GetMapping("/orders/{orderId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(
        @PathVariable orderId: UUID,
        @RequestHeader("Last-Event-ID", required = false, defaultValue = "0") lastEventId: Long,
    ): SseEmitter = streams.open(orderId, lastEventId)

    @PostMapping("/operations/publications/replay-failed")
    fun replayFailed(
        @RequestParam(defaultValue = "10") batchSize: Int,
    ): ResponseEntity<Map<String, Int>> {
        val result = reconciliation.replayFailed(batchSize)
        return ResponseEntity.accepted().body(mapOf("requested" to result.requested))
    }

    @PostMapping("/operations/payments/{paymentAttemptId}/reconcile-delayed")
    fun reconcileDelayedPayment(
        @PathVariable paymentAttemptId: UUID,
    ): ResponseEntity<Map<String, String>> {
        val disposition = paymentEvents.reconcileDelayedSuccess(paymentAttemptId)
        return ResponseEntity.accepted().body(mapOf("disposition" to disposition.name))
    }

    companion object : KLogging()
}
