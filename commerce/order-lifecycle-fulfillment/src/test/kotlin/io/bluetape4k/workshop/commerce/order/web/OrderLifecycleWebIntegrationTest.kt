package io.bluetape4k.workshop.commerce.order.web

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.commerce.order.AbstractOrderLifecycleIntegrationTest
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.ObjectMapper
import java.time.Duration

internal class OrderLifecycleWebIntegrationTest(
    private val objectMapper: ObjectMapper,
) : AbstractOrderLifecycleIntegrationTest() {
    @Test
    fun `browser console is served from the application root`() {
        val root =
            webTestClient
                .get()
                .uri("/")
                .exchange()
                .expectStatus()
                .isOk
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
                .shouldNotBeNull()
        root shouldContain "Order Lifecycle Console"

        val script =
            webTestClient
                .get()
                .uri("/app.js")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
                .shouldNotBeNull()
        script shouldContain "advanceFulfillment"
        script shouldContain "cancelLine"
        script shouldContain "cancellableQuantity"
        script shouldContain "reconcileDelayed"
    }

    @Test
    fun `same idempotency key replays response and conflicting payload is rejected`() {
        val first =
            submitOrder(IDEMPOTENCY_KEY, ORDER_JSON)
                .expectStatus()
                .isCreated
                .expectHeader()
                .valueEquals("Idempotency-Replayed", "false")
                .expectBody(String::class.java)
                .returnResult()
        val firstBody = first.responseBody.shouldNotBeNull()
        val orderId = objectMapper.readTree(firstBody).get("orderId").asString()

        submitOrder(IDEMPOTENCY_KEY, ORDER_JSON)
            .expectStatus()
            .isCreated
            .expectHeader()
            .valueEquals("Idempotency-Replayed", "true")
            .expectBody()
            .json(firstBody)

        submitOrder(IDEMPOTENCY_KEY, ORDER_JSON.replace("sku-a", "sku-conflict"))
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("IDEMPOTENCY_FINGERPRINT_CONFLICT")

        await atMost Duration.ofSeconds(10) untilAsserted {
            webTestClient
                .get()
                .uri("/api/v1/orders/{orderId}", orderId)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.order.id")
                .isEqualTo(orderId)
                .jsonPath("$.order.status")
                .isEqualTo("FULFILLMENT_IN_PROGRESS")
                .jsonPath("$.payment.revision")
                .isEqualTo(2)
                .jsonPath("$.fulfillments.length()")
                .isEqualTo(2)
        }
    }

    @Test
    fun `browser commands reconcile delayed payment and keep cancellation separate from refund`() {
        val delayed =
            submitOrder(
                "delayed-key-0001",
                ORDER_JSON.replace("web-ref", "delayed-ref").replace("SUCCESS", "DELAYED_SUCCESS")
            ).expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
        val delayedBody = objectMapper.readTree(delayed.responseBody.shouldNotBeNull())
        val delayedOrderId = delayedBody.get("orderId").asString()
        val paymentAttemptId = delayedBody.get("paymentAttemptId").asString()

        await atMost Duration.ofSeconds(10) untilAsserted {
            webTestClient
                .get()
                .uri("/api/v1/orders/{orderId}", delayedOrderId)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.payment.status")
                .isEqualTo("AUTHORIZING")
        }

        webTestClient
            .post()
            .uri("/api/v1/operations/payments/{paymentAttemptId}/reconcile-delayed", paymentAttemptId)
            .exchange()
            .expectStatus()
            .isAccepted
            .expectBody()
            .jsonPath("$.disposition")
            .isEqualTo("APPLIED")

        await atMost Duration.ofSeconds(10) untilAsserted {
            webTestClient
                .get()
                .uri("/api/v1/orders/{orderId}", delayedOrderId)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.payment.status")
                .isEqualTo("SUCCEEDED")
        }

        val snapshot =
            webTestClient
                .get()
                .uri("/api/v1/orders/{orderId}", delayedOrderId)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
                .shouldNotBeNull()
        val lineId =
            objectMapper
                .readTree(snapshot)
                .get("lines")
                .get(1)
                .get("lineId")
                .asString()
        webTestClient
            .post()
            .uri("/api/v1/orders/{orderId}/lines/{lineId}/cancel", delayedOrderId, lineId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"quantity":1,"reasonCode":"CUSTOMER_REQUEST"}""")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.cancellationCaseId")
            .isNotEmpty
            .jsonPath("$.refundCaseId")
            .isNotEmpty

        await atMost Duration.ofSeconds(10) untilAsserted {
            webTestClient
                .get()
                .uri("/api/v1/orders/{orderId}", delayedOrderId)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.lines[1].cancelledQuantity")
                .isEqualTo(1)
                .jsonPath("$.cancellations[0].status")
                .isEqualTo("APPROVED")
                .jsonPath("$.refunds[0].status")
                .isEqualTo("SUCCEEDED")
        }
    }

    @Test
    fun `event stream starts asynchronously with snapshot first and accepts audit cursor`() {
        val created =
            submitOrder("sse-key-0001", ORDER_JSON.replace("web-ref", "sse-ref"))
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
        val orderId = objectMapper.readTree(created.responseBody.shouldNotBeNull()).get("orderId").asString()

        await atMost Duration.ofSeconds(10) untilAsserted {
            webTestClient
                .get()
                .uri("/api/v1/orders/{orderId}", orderId)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.order.status")
                .isEqualTo("FULFILLMENT_IN_PROGRESS")
        }

        val firstEvent =
            webTestClient
                .get()
                .uri("/api/v1/orders/{orderId}/events", orderId)
                .header("Last-Event-ID", "0")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus()
                .isOk
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String::class.java)
                .responseBody
                .blockFirst(Duration.ofSeconds(5))
                .shouldNotBeNull()
        firstEvent shouldContain orderId
    }

    private fun submitOrder(
        idempotencyKey: String,
        json: String,
    ): WebTestClient.ResponseSpec =
        webTestClient
            .post()
            .uri("/api/v1/orders")
            .header(IDEMPOTENCY_HEADER, idempotencyKey)
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(json)
            .exchange()

    companion object {
        private const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        private const val IDEMPOTENCY_KEY = "order-key-0001"
        private val ORDER_JSON =
            """
            {
              "tenantId": "tenant-web",
              "customerReference": "web-ref",
              "providerMode": "SUCCESS",
              "lines": [
                {"sku": "sku-a", "quantity": 1, "unitPrice": 10.00},
                {"sku": "sku-b", "quantity": 2, "unitPrice": 20.00}
              ]
            }
            """.trimIndent()
    }
}
