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
 * idempotent order creation 을 처리하는 REST controller 입니다.
 *
 * ## 동작 / 계약
 * - `POST /api/orders` 는 `Idempotency-Key` header 를 요구합니다.
 * - `Idempotency-Key` 가 없거나 비어 있으면 HTTP 400 을 반환합니다.
 * - 특정 key 의 첫 요청은 새 [OrderResponse] 와 함께 HTTP 201 을 반환합니다.
 * - TTL 안에서 같은 key 로 반복 요청하면 원래 [OrderResponse] 와 함께 HTTP 200 을 반환합니다.
 */
@RestController
@RequestMapping("/api/orders")
class OrderController(private val idempotencyService: IdempotencyService) {

    companion object : KLogging() {
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
    }

    /**
     * 중복에 안전한 방식으로 주문을 생성합니다.
     *
     * client 는 `Idempotency-Key` header 를 제공해야 합니다(UUID 권장).
     * 5분 안에 같은 key 로 요청을 재시도하면 원래 response 를 반환합니다.
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
