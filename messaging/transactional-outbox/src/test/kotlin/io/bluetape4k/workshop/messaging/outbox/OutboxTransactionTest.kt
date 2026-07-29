package io.bluetape4k.workshop.messaging.outbox

import com.ninjasquad.springmockk.MockkBean
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.messaging.outbox.api.OrderRequest
import io.bluetape4k.workshop.messaging.outbox.api.OrderResponse
import io.bluetape4k.workshop.messaging.outbox.api.UpdateStatusRequest
import io.bluetape4k.workshop.messaging.outbox.domain.OrderService
import io.bluetape4k.workshop.messaging.outbox.domain.OrderStatus
import io.bluetape4k.workshop.messaging.outbox.domain.OutboxEventTable
import io.bluetape4k.workshop.messaging.outbox.outbox.OutboxPublisher
import io.bluetape4k.workshop.messaging.outbox.outbox.OutboxStatus
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CompletableFuture

/**
 * Transactional Outbox pattern 의 integration test 입니다.
 *
 * `@MockkSpyBean` 의 generic-type-erasure 문제를 피하고 test 별 publish success/failure 를 제어하기 위해 `KafkaTemplate` 에 `@MockkBean` 을 사용합니다.
 *
 * ## Test coverage
 * 1. order place → order row 와 outbox event row 가 atomically 생성됩니다.
 * 2. HTTP POST → order 가 생성되고 HTTP 201 이 반환됩니다.
 * 3. HTTP PUT → order status 가 update 됩니다.
 * 4. publishEvent → event 가 PUBLISHED 로 표시됩니다.
 * 5. failed publish → retryCount 가 증가하고 status 가 FAILED 가 됩니다.
 * 6. max-retry exceeded → status 가 DEAD_LETTER 로 transition 합니다.
 * 7. duplicate publish call → idempotent 합니다. false 를 반환하고 status 는 바뀌지 않습니다.
 */
class OutboxTransactionTest : AbstractOutboxTest() {
    companion object : KLogging()

    @Autowired
    private lateinit var orderService: OrderService

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @MockkBean(relaxed = true)
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @BeforeEach
    fun stubSuccessfulSend() {
        // 기본값: Kafka send 는 성공합니다.
        val successFuture: CompletableFuture<SendResult<String, String>> =
            CompletableFuture.completedFuture(mockk())
        every { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) } returns successFuture
    }

    // ── 1. atomic creation ──────────────────────────────────────────────────

    @Test
    fun `placeOrder creates order and outbox event in same transaction`() {
        val order = orderService.placeOrder(
            customerId = faker.name().username(),
            product = faker.commerce().productName(),
            quantity = faker.number().numberBetween(1, 10),
        )

        order.shouldNotBeNull()
        order.id.shouldBeGreaterThan(0L)
        order.status shouldBeEqualTo OrderStatus.PENDING

        val eventCount = transactionTemplate.execute {
            OutboxEventTable.selectAll()
                .where { OutboxEventTable.aggregateId eq order.id.toString() }
                .count()
        }.shouldNotBeNull()
        eventCount shouldBeEqualTo 1L
        log.debug { "placeOrder: orderId=${order.id}, outboxEventCount=$eventCount" }
    }

    // ── 2. HTTP POST ──────────────────────────────────────────────────────────

    @Test
    fun `POST api-orders creates order and returns 201`() {
        val request = OrderRequest(
            customerId = faker.name().username(),
            product = faker.commerce().productName(),
            quantity = 3,
        )

        webTestClient.post().uri("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated
            .expectBody(OrderResponse::class.java)
            .value { it.shouldNotBeNull().id.shouldBeGreaterThan(0L) }
    }

    // ── 3. HTTP PUT ───────────────────────────────────────────────────────────

    @Test
    fun `PUT api-orders-id-status updates order status`() {
        val order = orderService.placeOrder(
            customerId = faker.name().username(),
            product = faker.commerce().productName(),
            quantity = 2,
        )

        webTestClient.put().uri("/api/orders/${order.id}/status")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(UpdateStatusRequest(status = OrderStatus.CONFIRMED))
            .exchange()
            .expectStatus().isOk
            .expectBody(OrderResponse::class.java)
            .value { it.shouldNotBeNull().status shouldBeEqualTo OrderStatus.CONFIRMED }
    }

    // ── 4. publish succeeds ──────────────────────────────────────────────────

    @Test
    fun `publishEvent publishes to Kafka and marks event PUBLISHED`() {
        val order = orderService.placeOrder(
            customerId = faker.name().username(),
            product = faker.commerce().productName(),
            quantity = 1,
        )

        val eventId = transactionTemplate.execute {
            OutboxEventTable.selectAll()
                .where { OutboxEventTable.aggregateId eq order.id.toString() }
                .map { it[OutboxEventTable.id].value }
                .first()
        }.shouldNotBeNull()

        val result = outboxPublisher.publishEvent(eventId)
        result.shouldBeTrue()

        val status = transactionTemplate.execute {
            OutboxEventTable.selectAll()
                .where { OutboxEventTable.id eq eventId }
                .map { it[OutboxEventTable.status] }
                .first()
        }.shouldNotBeNull()
        status shouldBeEqualTo OutboxStatus.PUBLISHED
    }

    // ── 5. failed publish → FAILED + retryCount++ ─────────────────────────

    @Test
    fun `failed publish increments retry count and sets status FAILED`() {
        val order = orderService.placeOrder(
            customerId = faker.name().username(),
            product = faker.commerce().productName(),
            quantity = 1,
        )

        val eventId = transactionTemplate.execute {
            OutboxEventTable.selectAll()
                .where { OutboxEventTable.aggregateId eq order.id.toString() }
                .map { it[OutboxEventTable.id].value }
                .first()
        }.shouldNotBeNull()

        every {
            kafkaTemplate.send(any<String>(), any<String>(), any<String>())
        } throws RuntimeException("Kafka unavailable")

        val result = outboxPublisher.publishEvent(eventId)
        result.shouldBeFalse()

        val row = transactionTemplate.execute {
            OutboxEventTable.selectAll()
                .where { OutboxEventTable.id eq eventId }
                .map { Pair(it[OutboxEventTable.retryCount], it[OutboxEventTable.status]) }
                .first()
        }.shouldNotBeNull()
        row.first shouldBeEqualTo 1
        row.second shouldBeEqualTo OutboxStatus.FAILED
    }

    // ── 6. max retry → DEAD_LETTER ───────────────────────────────────────────

    @Test
    fun `event exceeding max retries moves to DEAD_LETTER`() {
        val order = orderService.placeOrder(
            customerId = faker.name().username(),
            product = faker.commerce().productName(),
            quantity = 1,
        )

        val eventId = transactionTemplate.execute {
            OutboxEventTable.selectAll()
                .where { OutboxEventTable.aggregateId eq order.id.toString() }
                .map { it[OutboxEventTable.id].value }
                .first()
        }.shouldNotBeNull()

        // Pre-set retryCount to MAX_RETRY - 1 so next failure tips it over
        transactionTemplate.execute {
            OutboxEventTable.update({ OutboxEventTable.id eq eventId }) {
                it[OutboxEventTable.retryCount] = OutboxPublisher.MAX_RETRY - 1
                it[OutboxEventTable.status] = OutboxStatus.FAILED
            }
        }

        every {
            kafkaTemplate.send(any<String>(), any<String>(), any<String>())
        } throws RuntimeException("Kafka unavailable")

        outboxPublisher.publishEvent(eventId)

        val status = transactionTemplate.execute {
            OutboxEventTable.selectAll()
                .where { OutboxEventTable.id eq eventId }
                .map { it[OutboxEventTable.status] }
                .first()
        }.shouldNotBeNull()
        status shouldBeEqualTo OutboxStatus.DEAD_LETTER
    }

    // ── 7. Idempotent publish ─────────────────────────────────────────────────

    @Test
    fun `duplicate publish call is idempotent`() {
        val order = orderService.placeOrder(
            customerId = faker.name().username(),
            product = faker.commerce().productName(),
            quantity = 1,
        )

        val eventId = transactionTemplate.execute {
            OutboxEventTable.selectAll()
                .where { OutboxEventTable.aggregateId eq order.id.toString() }
                .map { it[OutboxEventTable.id].value }
                .first()
        }.shouldNotBeNull()

        outboxPublisher.publishEvent(eventId).shouldBeTrue()

        // Second call must return false (already PUBLISHED)
        outboxPublisher.publishEvent(eventId).shouldBeFalse()

        val status = transactionTemplate.execute {
            OutboxEventTable.selectAll()
                .where { OutboxEventTable.id eq eventId }
                .map { it[OutboxEventTable.status] }
                .first()
        }.shouldNotBeNull()
        status shouldBeEqualTo OutboxStatus.PUBLISHED
    }
}
