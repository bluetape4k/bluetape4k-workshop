package io.bluetape4k.workshop.idempotency.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.idempotency.model.OrderRequest
import io.bluetape4k.workshop.idempotency.model.OrderResponse
import io.bluetape4k.workshop.idempotency.service.IdempotencyResult
import io.bluetape4k.workshop.idempotency.service.IdempotencyService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * REST controller for idempotent order creation.
 *
 * ## Behavior / Contract
 * - `POST /api/orders` requires an `Idempotency-Key` header.
 * - A missing or blank `Idempotency-Key` returns HTTP 400.
 * - First request with a key returns HTTP 201 with the new [OrderResponse].
 * - Repeated request with the same key within the TTL returns HTTP 200 with the original [OrderResponse].
 */
@RestController
@RequestMapping("/api/orders")
class OrderController(private val idempotencyService: IdempotencyService) {

    companion object : KLogging() {
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
    }

    /**
     * Creates an order in a duplicate-safe manner.
     *
     * Clients must supply an `Idempotency-Key` header (UUID recommended).
     * Retrying the request with the same key within 5 minutes returns the original response.
     */
    @PostMapping
    suspend fun createOrder(
        @RequestHeader(IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        @RequestBody request: OrderRequest,
    ): ResponseEntity<OrderResponse> {
        val key = requireIdempotencyKey(idempotencyKey)

        log.debug { "Processing order with idempotency key=$key, request=$request" }

        return when (val result = idempotencyService.processOrder(key, request)) {
            is IdempotencyResult.Created ->
                ResponseEntity.status(HttpStatus.CREATED).body(result.cached.response)

            is IdempotencyResult.Replay ->
                ResponseEntity.ok(result.cached.response)
        }
    }

    private fun requireIdempotencyKey(idempotencyKey: String?): String {
        val key = idempotencyKey?.trim().orEmpty()
        if (key.isBlank()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Header '$IDEMPOTENCY_KEY_HEADER' is required and must not be blank",
            )
        }
        return key.requireNotBlank(IDEMPOTENCY_KEY_HEADER)
    }
}
