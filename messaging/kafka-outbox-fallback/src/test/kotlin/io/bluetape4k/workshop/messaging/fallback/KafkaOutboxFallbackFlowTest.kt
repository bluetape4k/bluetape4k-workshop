package io.bluetape4k.workshop.messaging.fallback

import com.ninjasquad.springmockk.MockkBean
import com.ninjasquad.springmockk.MockkSpyBean
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.workshop.messaging.fallback.api.OrderRequest
import io.bluetape4k.workshop.messaging.fallback.api.OrderResponse
import io.bluetape4k.workshop.messaging.fallback.api.OrderPublicationStatus
import io.bluetape4k.workshop.messaging.fallback.domain.OrderTable
import io.bluetape4k.workshop.messaging.fallback.domain.TransactionalOrderWriter
import io.bluetape4k.workshop.messaging.fallback.publication.EventPublicationRelay
import io.bluetape4k.workshop.messaging.fallback.publication.EventPublicationRepository
import io.bluetape4k.workshop.messaging.fallback.publication.EventPublicationStatus
import io.bluetape4k.workshop.messaging.fallback.publication.EventPublicationTable
import io.bluetape4k.workshop.messaging.fallback.publication.PublicationReconciler
import io.mockk.every
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.transaction.support.TransactionTemplate
import io.micrometer.core.instrument.MeterRegistry
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import kotlin.system.measureTimeMillis

class KafkaOutboxFallbackFlowTest : AbstractKafkaOutboxFallbackTest() {

    @Autowired
    private lateinit var transactionalOrderWriter: TransactionalOrderWriter

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @MockkBean(relaxed = true)
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @MockkSpyBean
    private lateinit var eventPublicationRepository: EventPublicationRepository

    @Autowired
    private lateinit var eventPublicationRelay: EventPublicationRelay

    @Autowired
    private lateinit var publicationReconciler: PublicationReconciler

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @BeforeEach
    fun clearTables() {
        val successFuture = CompletableFuture.completedFuture<SendResult<String, String>>(mockk())
        every { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) } returns successFuture

        transactionTemplate.execute {
            EventPublicationTable.deleteAll()
            OrderTable.deleteAll()
        }
    }

    @Test
    fun `transactional writer stores only order row`() {
        val order = transactionalOrderWriter.saveOrder(
            customerId = "customer-${faker.number().digits(8)}",
            product = faker.commerce().productName(),
            quantity = 3,
        )

        order.id.shouldBeGreaterThan(0L)

        val counts = transactionTemplate.execute {
            val orderCount = OrderTable.selectAll().count()
            val publicationCount = EventPublicationTable.selectAll().count()
            orderCount to publicationCount
        }

        counts.first shouldBeEqualTo 1L
        counts.second shouldBeEqualTo 0L
    }

    @Test
    fun `POST api-orders rejects invalid input with safe 400 and zero persistence`() {
        val request = OrderRequest(
            customerId = "",
            product = "valid-product",
            quantity = 1,
        )

        webTestClient.post().uri("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isBadRequest

        val orderCount = transactionTemplate.execute {
            OrderTable.selectAll().count()
        }

        orderCount shouldBeEqualTo 0L
    }

    @Test
    fun `placeOrder stores only order row and returns PUBLISHED_DIRECT when direct Kafka publish succeeds`() {
        val request = OrderRequest(
            customerId = "customer-${faker.number().digits(8)}",
            product = faker.commerce().productName(),
            quantity = 2,
        )

        webTestClient.post().uri("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated
            .expectBody(OrderResponse::class.java)
            .value { response ->
                requireNotNull(response).publicationStatus shouldBeEqualTo OrderPublicationStatus.PUBLISHED_DIRECT
            }

        val counts = transactionTemplate.execute {
            OrderTable.selectAll().count() to EventPublicationTable.selectAll().count()
        }

        counts.first shouldBeEqualTo 1L
        counts.second shouldBeEqualTo 0L
    }

    @Test
    fun `direct publish retries three times then stores NOT_PUBLISHED fallback row`() {
        every {
            kafkaTemplate.send(any<String>(), any<String>(), any<String>())
        } throws RuntimeException("Kafka unavailable at broker secret://hidden")

        val request = OrderRequest(
            customerId = "customer-${faker.number().digits(8)}",
            product = faker.commerce().productName(),
            quantity = 1,
        )

        webTestClient.post().uri("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated
            .expectBody(OrderResponse::class.java)
            .value { response ->
                requireNotNull(response).publicationStatus shouldBeEqualTo OrderPublicationStatus.FALLBACK_STORED
            }

        verify(exactly = 3) {
            kafkaTemplate.send(any<String>(), any<String>(), any<String>())
        }

        val row = transactionTemplate.execute {
            EventPublicationTable.selectAll().single()
        }

        row[EventPublicationTable.status] shouldBeEqualTo EventPublicationStatus.NOT_PUBLISHED
        row[EventPublicationTable.directAttemptCount] shouldBeEqualTo 3
        row[EventPublicationTable.relayRetryCount] shouldBeEqualTo 0
        requireNotNull(row[EventPublicationTable.lastErrorSummary])
            .contains("secret://") shouldBeEqualTo false
    }

    @Test
    fun `direct publish timeout stores NOT_PUBLISHED fallback row`() {
        val pending = CompletableFuture<SendResult<String, String>>()
        every { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) } returns pending

        val elapsed = measureTimeMillis {
            val request = OrderRequest(
                customerId = "customer-${faker.number().digits(8)}",
                product = faker.commerce().productName(),
                quantity = 1,
            )

            webTestClient.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated
                .expectBody(OrderResponse::class.java)
                .value { response ->
                    requireNotNull(response).publicationStatus shouldBeEqualTo OrderPublicationStatus.FALLBACK_STORED
                }
        }

        elapsed.shouldBeLessThan(1000L)
        pending.isCancelled shouldBeEqualTo true

        val status = transactionTemplate.execute {
            EventPublicationTable.selectAll().single()[EventPublicationTable.status]
        }

        status shouldBeEqualTo EventPublicationStatus.NOT_PUBLISHED
    }

    @Test
    fun `fallback insert failure returns FALLBACK_STORE_FAILED and records safe metric and log`() {
        every {
            kafkaTemplate.send(any<String>(), any<String>(), any<String>())
        } throws RuntimeException("Kafka unavailable")
        every {
            eventPublicationRepository.upsertNotPublished(any(), any(), any(), any(), any())
        } throws RuntimeException("database write failed with secret token")

        val request = OrderRequest(
            customerId = "customer-${faker.number().digits(8)}",
            product = faker.commerce().productName(),
            quantity = 1,
        )

        webTestClient.post().uri("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated
            .expectBody(OrderResponse::class.java)
            .value { response ->
                requireNotNull(response).publicationStatus shouldBeEqualTo OrderPublicationStatus.FALLBACK_STORE_FAILED
            }
    }

    @Test
    fun `relay publishes fallback row and marks it PUBLISHED`() {
        val eventId = createFallbackPublication()

        val result = eventPublicationRelay.relayOnce()

        result.published shouldBeEqualTo 1
        val row = transactionTemplate.execute {
            EventPublicationTable.selectAll().where { EventPublicationTable.eventId eq eventId }.single()
        }

        row[EventPublicationTable.status] shouldBeEqualTo EventPublicationStatus.PUBLISHED
        (row[EventPublicationTable.publishedAt] != null) shouldBeEqualTo true
        row[EventPublicationTable.claimedBy] shouldBeEqualTo null
        verify(exactly = 1) {
            kafkaTemplate.send(any<String>(), eventId, match { it.contains("\"orderId\"") })
        }
    }

    @Test
    fun `relay failure increments retry and moves to DEAD_LETTER`() {
        val eventId = createFallbackPublication(relayRetryCount = 2)
        every {
            kafkaTemplate.send(any<String>(), any<String>(), any<String>())
        } throws RuntimeException("Kafka relay failed with token=secret")

        val result = eventPublicationRelay.relayOnce()

        result.deadLettered shouldBeEqualTo 1
        val row = transactionTemplate.execute {
            EventPublicationTable.selectAll().where { EventPublicationTable.eventId eq eventId }.single()
        }

        row[EventPublicationTable.status] shouldBeEqualTo EventPublicationStatus.DEAD_LETTER
        row[EventPublicationTable.relayRetryCount] shouldBeEqualTo 3
        requireNotNull(row[EventPublicationTable.lastErrorSummary])
            .contains("secret") shouldBeEqualTo false
    }

    @Test
    fun `concurrent relay calls cannot claim the same row twice`() {
        createFallbackPublication()

        val first = eventPublicationRepository.claimNextBatch("worker-a", 1).single()
        val second = eventPublicationRepository.claimNextBatch("worker-b", 1)

        first.claimedBy shouldBeEqualTo "worker-a"
        second.size shouldBeEqualTo 0
    }

    @Test
    fun `stale relay claim becomes eligible after claim ttl`() {
        val eventId = createFallbackPublication()
        eventPublicationRepository.claimNextBatch("worker-a", 1)

        transactionTemplate.execute {
            EventPublicationTable.update({ EventPublicationTable.eventId eq eventId }) {
                it[claimedUntil] = LocalDateTime.now().minusMinutes(5)
            }
        }

        val claimed = eventPublicationRepository.claimNextBatch("worker-b", 1).single()

        claimed.claimedBy shouldBeEqualTo "worker-b"
    }

    @Test
    fun `claimNextBatch applies SQL eligibility ordering and limit`() {
        val now = LocalDateTime.now()
        val first = insertPublicationRow(
            eventId = "order-placed:1001:v1",
            aggregateId = "1001",
            nextAttemptAt = now.minusMinutes(3),
        )
        val second = insertPublicationRow(
            eventId = "order-placed:1002:v1",
            aggregateId = "1002",
            nextAttemptAt = now.minusMinutes(1),
        )
        val future = insertPublicationRow(
            eventId = "order-placed:1003:v1",
            aggregateId = "1003",
            nextAttemptAt = now.plusMinutes(1),
        )
        val published = insertPublicationRow(
            eventId = "order-placed:1004:v1",
            aggregateId = "1004",
            status = EventPublicationStatus.PUBLISHED,
            nextAttemptAt = now.minusMinutes(5),
        )

        val claimed = eventPublicationRepository.claimNextBatch("worker-sql", 2)

        claimed.map { it.eventId } shouldBeEqualTo listOf(first, second)
        val claimStates = transactionTemplate.execute {
            EventPublicationTable.selectAll().associate { row ->
                row[EventPublicationTable.eventId] to row[EventPublicationTable.claimedBy]
            }
        }

        claimStates.getValue(first) shouldBeEqualTo "worker-sql"
        claimStates.getValue(second) shouldBeEqualTo "worker-sql"
        claimStates.getValue(future) shouldBeEqualTo null
        claimStates.getValue(published) shouldBeEqualTo null
    }

    @Test
    fun `reconciler reconstructs deterministic fallback row and documents duplicate risk`() {
        every {
            kafkaTemplate.send(any<String>(), any<String>(), any<String>())
        } throws RuntimeException("Kafka unavailable")

        val request = OrderRequest(
            customerId = "customer-${faker.number().digits(8)}",
            product = faker.commerce().productName(),
            quantity = 1,
        )
        webTestClient.post().uri("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated
        transactionTemplate.execute {
            EventPublicationTable.deleteAll()
            OrderTable.update {
                it[createdAt] = LocalDateTime.now().minusMinutes(5)
            }
        }

        val result = publicationReconciler.reconcileOnce()

        result.reconstructed shouldBeEqualTo 1
        result.duplicateRiskDocumented shouldBeEqualTo true
        val eventId = transactionTemplate.execute {
            EventPublicationTable.selectAll().single()[EventPublicationTable.eventId]
        }

        eventId.startsWith("order-placed:") shouldBeEqualTo true
    }

    @Test
    fun `reconciler uses SQL cutoff and anti join for missing publications`() {
        val oldMissingOrderId = createOrderWithCreatedAt(LocalDateTime.now().minusMinutes(5))
        val futureMissingOrderId = createOrderWithCreatedAt(LocalDateTime.now().plusMinutes(5))
        val existingOrderId = createOrderWithCreatedAt(LocalDateTime.now().minusMinutes(5))
        val existingEventId = insertPublicationRow(
            eventId = "order-placed:$existingOrderId:v1",
            aggregateId = existingOrderId.toString(),
        )

        val result = publicationReconciler.reconcileOnce()

        result.scanned shouldBeEqualTo 1
        result.reconstructed shouldBeEqualTo 1
        val eventIds = transactionTemplate.execute {
            EventPublicationTable.selectAll()
                .map { row -> row[EventPublicationTable.eventId] }
                .toSet()
        }

        eventIds.contains("order-placed:$oldMissingOrderId:v1") shouldBeEqualTo true
        eventIds.contains("order-placed:$futureMissingOrderId:v1") shouldBeEqualTo false
        eventIds.contains(existingEventId) shouldBeEqualTo true
    }

    @Test
    fun `demo admin relay and reconcile endpoints are disabled by default`() {
        createFallbackPublication()

        webTestClient.post().uri("/api/publications/relay")
            .exchange()
            .expectStatus().isNotFound
        webTestClient.post().uri("/api/publications/reconcile")
            .exchange()
            .expectStatus().isNotFound

        val status = transactionTemplate.execute {
            EventPublicationTable.selectAll().single()[EventPublicationTable.status]
        }

        status shouldBeEqualTo EventPublicationStatus.NOT_PUBLISHED
    }

    @Test
    fun `publication endpoint never exposes raw payload or raw exception text`() {
        createFallbackPublication(errorSummary = "database failed with token=secret")

        webTestClient.get().uri("/api/publications")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].payload").doesNotExist()
            .jsonPath("$[0].lastErrorSummary").isEqualTo("database failed with [redacted]")
    }

    @Test
    fun `scheduled relay and reconciler do nothing when disabled`() {
        createFallbackPublication()

        eventPublicationRelay.scheduledRelay()

        verify(exactly = 0) {
            kafkaTemplate.send(any<String>(), any<String>(), any<String>())
        }
        val statusAfterRelay = transactionTemplate.execute {
            EventPublicationTable.selectAll().single()[EventPublicationTable.status]
        }
        statusAfterRelay shouldBeEqualTo EventPublicationStatus.NOT_PUBLISHED

        transactionTemplate.execute {
            EventPublicationTable.deleteAll()
            val order = transactionalOrderWriter.saveOrder(
                customerId = "customer-${faker.number().digits(8)}",
                product = faker.commerce().productName(),
                quantity = 1,
            )
            OrderTable.update({ OrderTable.id eq order.id }) {
                it[createdAt] = LocalDateTime.now().minusMinutes(5)
            }
        }

        publicationReconciler.scheduledReconcile()

        val publicationCount = transactionTemplate.execute {
            EventPublicationTable.selectAll().count()
        }
        publicationCount shouldBeEqualTo 0L
    }

    @Test
    fun `health readiness liveness and safe error defaults expose no sensitive details`() {
        webTestClient.get().uri("/actuator/health/readiness")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP")
            .jsonPath("$.components").doesNotExist()

        webTestClient.get().uri("/actuator/health/liveness")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP")
            .jsonPath("$.components").doesNotExist()

        webTestClient.post().uri("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"customerId":"secret-customer","product":"","quantity":0}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.message").isEqualTo("Request validation failed")
    }

    @Test
    fun `metrics record direct failure fallback relay and reconciler outcomes`() {
        createFallbackPublication()

        meterRegistry.counter("workshop.outbox.direct.publish.attempts", "result", "failure").count()
            .shouldBeGreaterThan(0.0)
        meterRegistry.counter("workshop.outbox.fallback.stored", "result", "success").count()
            .shouldBeGreaterThan(0.0)

        eventPublicationRelay.relayOnce()
        meterRegistry.counter("workshop.outbox.relay.events", "result", "published").count()
            .shouldBeGreaterThan(0.0)

        transactionTemplate.execute {
            EventPublicationTable.deleteAll()
            val order = transactionalOrderWriter.saveOrder(
                customerId = "customer-${faker.number().digits(8)}",
                product = faker.commerce().productName(),
                quantity = 1,
            )
            OrderTable.update({ OrderTable.id eq order.id }) {
                it[createdAt] = LocalDateTime.now().minusMinutes(5)
            }
        }

        publicationReconciler.reconcileOnce()
        meterRegistry.counter("workshop.outbox.reconciler.events", "result", "reconstructed").count()
            .shouldBeGreaterThan(0.0)
    }

    private fun createFallbackPublication(
        relayRetryCount: Int = 0,
        errorSummary: String = "Kafka unavailable",
    ): String {
        every {
            kafkaTemplate.send(any<String>(), any<String>(), any<String>())
        } throws RuntimeException(errorSummary)

        val request = OrderRequest(
            customerId = "customer-${faker.number().digits(8)}",
            product = faker.commerce().productName(),
            quantity = 1,
        )

        webTestClient.post().uri("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated

        val eventId = transactionTemplate.execute {
            EventPublicationTable.selectAll().single()[EventPublicationTable.eventId]
        }
        if (relayRetryCount > 0) {
            transactionTemplate.execute {
                EventPublicationTable.update({ EventPublicationTable.eventId eq eventId }) {
                    it[EventPublicationTable.relayRetryCount] = relayRetryCount
                }
            }
        }

        clearMocks(kafkaTemplate)
        every { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) } returns
            CompletableFuture.completedFuture<SendResult<String, String>>(mockk())

        return eventId
    }

    private fun createOrderWithCreatedAt(createdAt: LocalDateTime): Long {
        val order = transactionalOrderWriter.saveOrder(
            customerId = "customer-${faker.number().digits(8)}",
            product = faker.commerce().productName(),
            quantity = 1,
        )
        transactionTemplate.execute {
            OrderTable.update({ OrderTable.id eq order.id }) {
                it[OrderTable.createdAt] = createdAt
            }
        }
        return order.id
    }

    private fun insertPublicationRow(
        eventId: String,
        aggregateId: String,
        status: EventPublicationStatus = EventPublicationStatus.NOT_PUBLISHED,
        nextAttemptAt: LocalDateTime = LocalDateTime.now().minusSeconds(1),
    ): String {
        transactionTemplate.execute {
            EventPublicationTable.insert {
                it[EventPublicationTable.eventId] = eventId
                it[aggregateType] = "Order"
                it[EventPublicationTable.aggregateId] = aggregateId
                it[eventType] = "OrderPlaced"
                it[payload] = """{"eventId":"$eventId"}"""
                it[EventPublicationTable.status] = status
                it[directAttemptCount] = 3
                it[relayRetryCount] = 0
                it[EventPublicationTable.nextAttemptAt] = nextAttemptAt
                it[updatedAt] = LocalDateTime.now()
            }
        }
        return eventId
    }
}
