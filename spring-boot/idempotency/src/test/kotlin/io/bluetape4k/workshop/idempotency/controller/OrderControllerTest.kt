package io.bluetape4k.workshop.idempotency.controller

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.idempotency.AbstractIdempotencyTest
import io.bluetape4k.workshop.idempotency.model.OrderRequest
import io.bluetape4k.workshop.idempotency.model.OrderResponse
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.expectBody

class OrderControllerTest : AbstractIdempotencyTest() {

    companion object : KLogging() {
        private const val ORDERS_PATH = "/api/orders"
        private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
    }

    private fun newIdempotencyKey(): String = Base58.randomString(16)

    private val sampleRequest = OrderRequest(
        productId = "prod-001",
        quantity = 2,
        userId = "user-123",
    )

    @Test
    fun `새 요청은 201 Created를 반환한다`() = runSuspendIO {
        client.post()
            .uri(ORDERS_PATH)
            .header(IDEMPOTENCY_KEY_HEADER, newIdempotencyKey())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(sampleRequest)
            .exchange()
            .expectStatus().isCreated
            .expectBody<OrderResponse>()
            .returnResult()
            .responseBody
            .shouldNotBeNull()
            .also { response ->
                response.orderId.shouldNotBeEmpty()
                response.status shouldBeEqualTo "CREATED"
            }
    }

    @Test
    fun `동일한 Idempotency-Key로 재전송하면 200 OK와 동일 응답을 반환한다`() = runSuspendIO {
        val key = newIdempotencyKey()

        // First request → 201 Created
        val first = client.post()
            .uri(ORDERS_PATH)
            .header(IDEMPOTENCY_KEY_HEADER, key)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(sampleRequest)
            .exchange()
            .expectStatus().isCreated
            .expectBody<OrderResponse>()
            .returnResult()
            .responseBody
            .shouldNotBeNull()

        // Second request with same key → 200 OK, same orderId
        val second = client.post()
            .uri(ORDERS_PATH)
            .header(IDEMPOTENCY_KEY_HEADER, key)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(sampleRequest)
            .exchange()
            .expectStatus().isOk
            .expectBody<OrderResponse>()
            .returnResult()
            .responseBody
            .shouldNotBeNull()

        second.orderId shouldBeEqualTo first.orderId
        second.processedAt shouldBeEqualTo first.processedAt
    }

    @Test
    fun `다른 Idempotency-Key는 새로운 주문을 생성한다`() = runSuspendIO {
        val keyA = newIdempotencyKey()
        val keyB = newIdempotencyKey()

        val responseA = client.post()
            .uri(ORDERS_PATH)
            .header(IDEMPOTENCY_KEY_HEADER, keyA)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(sampleRequest)
            .exchange()
            .expectStatus().isCreated
            .expectBody<OrderResponse>()
            .returnResult()
            .responseBody
            .shouldNotBeNull()

        val responseB = client.post()
            .uri(ORDERS_PATH)
            .header(IDEMPOTENCY_KEY_HEADER, keyB)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(sampleRequest)
            .exchange()
            .expectStatus().isCreated
            .expectBody<OrderResponse>()
            .returnResult()
            .responseBody
            .shouldNotBeNull()

        // Different keys → different order IDs
        responseA.orderId shouldNotBeEqualTo responseB.orderId
    }

    @Test
    fun `Idempotency-Key 헤더가 없으면 400 Bad Request를 반환한다`() = runSuspendIO {
        client.post()
            .uri(ORDERS_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(sampleRequest)
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `빈 Idempotency-Key 헤더는 400 Bad Request를 반환한다`() = runSuspendIO {
        client.post()
            .uri(ORDERS_PATH)
            .header(IDEMPOTENCY_KEY_HEADER, "   ")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(sampleRequest)
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `세 번 재전송해도 항상 동일한 응답을 반환한다`() = runSuspendIO {
        val key = newIdempotencyKey()

        val responses = (1..3).map {
            client.post()
                .uri(ORDERS_PATH)
                .header(IDEMPOTENCY_KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(sampleRequest)
                .exchange()
                .expectBody<OrderResponse>()
                .returnResult()
                .responseBody
                .shouldNotBeNull()
        }

        // All responses must carry the same orderId
        val distinctOrderIds = responses.map { it.orderId }.toSet()
        distinctOrderIds shouldHaveSize 1
    }
}
