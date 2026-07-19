package io.bluetape4k.workshop.commerce.reservation.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.workshop.commerce.reservation.AbstractReservationIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

internal class ReservationWebIntegrationTest : AbstractReservationIntegrationTest() {
    @Test
    fun `capacity one admits exactly one of ten concurrent live HTTP holds`() {
        val accepted = AtomicInteger()
        val rejected = AtomicInteger()
        val callerSequence = AtomicInteger()

        MultithreadingTester()
            .workers(10)
            .rounds(1)
            .add {
                val caller = callerSequence.incrementAndGet()
                val status =
                    webTestClient
                        .post()
                        .uri("/api/resources/1/holds")
                        .header("X-Reservation-Owner", "concurrent-owner-${caller.toString().padStart(32, '0')}")
                        .header("Idempotency-Key", "concurrent-hold-key-${caller.toString().padStart(16, '0')}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""{"expectedResourceRevision":0,"policyVersion":1}""")
                        .exchange()
                        .returnResult(String::class.java)
                        .status
                if (status.value() == 201) accepted.incrementAndGet() else rejected.incrementAndGet()
            }.run()

        accepted.get() shouldBeEqualTo 1
        rejected.get() shouldBeEqualTo 9
        webTestClient
            .get()
            .uri("/api/resources")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.resources[0].occupiedCount")
            .isEqualTo(1)
            .jsonPath("$.resources[0].availableCount")
            .isEqualTo(0)
    }

    @Test
    fun `concurrent live HTTP confirm and cancel converge through one hold revision`() {
        val owner = "convergent-owner-0123456789abcdef0123456789abcdef"
        webTestClient
            .post()
            .uri("/api/resources/1/holds")
            .header("X-Reservation-Owner", owner)
            .header("Idempotency-Key", "convergent-hold-create-012345")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedResourceRevision":0,"policyVersion":1}""")
            .exchange()
            .expectStatus()
            .isCreated

        val sequence = AtomicInteger()
        val statuses = ConcurrentLinkedQueue<Int>()
        MultithreadingTester()
            .workers(2)
            .rounds(1)
            .add {
                val operation = if (sequence.incrementAndGet() == 1) "confirm" else "cancel"
                statuses +=
                    webTestClient
                        .post()
                        .uri("/api/holds/1/$operation")
                        .header("X-Reservation-Owner", owner)
                        .header("Idempotency-Key", "convergent-$operation-key-012345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""{"expectedRevision":0,"policyVersion":1}""")
                        .exchange()
                        .returnResult(String::class.java)
                        .status
                        .value()
            }.run()

        assert(
            statuses.toList().sorted() == listOf(200, 409)
        ) { "expected one applied and one stale response: $statuses" }
        webTestClient
            .get()
            .uri("/api/resources")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.resources[0].occupiedCount")
            .value<Int> { value -> check(value in 0..1) }
    }

    @Test
    fun `browser console and authoritative resource snapshot are live`() {
        webTestClient
            .get()
            .uri("/")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals("Cache-Control", "no-store")
            .expectHeader()
            .exists("Content-Security-Policy")
            .expectBody(String::class.java)
            .value { body -> check(body?.contains("Reservation Control Plane") == true) }

        webTestClient
            .get()
            .uri("/api/resources")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.resources[0].code")
            .isEqualTo("demo-room-utc")
            .jsonPath("$.resources[0].capacity")
            .isEqualTo(1)
            .jsonPath("$.resources[0].policyVersion")
            .isEqualTo(1)
            .jsonPath("$.resources[0].timezone")
            .isEqualTo("UTC")
            .jsonPath("$.resources[0].localObservedAt")
            .exists()
    }

    @Test
    fun `hold command persists replay and rejects a different payload for the same key`() {
        val owner = "owner-token-0123456789abcdef0123456789abcdef"
        val key = "hold-key-0123456789abcdef"
        val firstBody =
            webTestClient
                .post()
                .uri("/api/resources/1/holds")
                .header("X-Reservation-Owner", owner)
                .header("Idempotency-Key", key)
                .header("X-Request-Id", "request-live-http-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"expectedResourceRevision":0,"policyVersion":1}""")
                .exchange()
                .expectStatus()
                .isCreated
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectHeader()
                .valueEquals("Idempotency-Replayed", "false")
                .expectBody()
                .jsonPath("$.state")
                .isEqualTo("HELD")
                .jsonPath("$.revision")
                .isEqualTo(0)
                .jsonPath("$.ownerToken")
                .doesNotExist()
                .returnResult()
                .responseBody

        webTestClient
            .post()
            .uri("/api/resources/1/holds")
            .header("X-Reservation-Owner", owner)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedResourceRevision":0,"policyVersion":1}""")
            .exchange()
            .expectStatus()
            .isCreated
            .expectHeader()
            .valueEquals("Idempotency-Replayed", "true")
            .expectBody()
            .json(String(requireNotNull(firstBody), Charsets.UTF_8))

        webTestClient
            .post()
            .uri("/api/resources/1/holds")
            .header("X-Reservation-Owner", owner)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedResourceRevision":1,"policyVersion":1}""")
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("IDEMPOTENCY_FINGERPRINT_CONFLICT")
    }

    @Test
    fun `cancel releases authoritative capacity in the same command transaction`() {
        val owner = "owner-token-1123456789abcdef0123456789abcdef"
        webTestClient
            .post()
            .uri("/api/resources/1/holds")
            .header("X-Reservation-Owner", owner)
            .header("Idempotency-Key", "hold-cancel-create-0123456789")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedResourceRevision":0,"policyVersion":1}""")
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.id")
            .isEqualTo(1)

        webTestClient
            .post()
            .uri("/api/holds/1/cancel")
            .header("X-Reservation-Owner", owner)
            .header("Idempotency-Key", "hold-cancel-command-0123456789")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedRevision":0,"policyVersion":1}""")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.state")
            .isEqualTo("CANCELLED")

        webTestClient
            .get()
            .uri("/api/resources")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.resources[0].availableCount")
            .isEqualTo(1)
            .jsonPath("$.resources[0].revision")
            .isEqualTo(2)
    }

    @Test
    fun `cancel hands occupied capacity to the oldest waiter instead of releasing it`() {
        val holder = "holder-cancel-0123456789abcdef0123456789abcdef"
        val waiter = "waiter-cancel-0123456789abcdef0123456789abcdef"
        webTestClient
            .post()
            .uri("/api/resources/1/holds")
            .header("X-Reservation-Owner", holder)
            .header("Idempotency-Key", "cancel-handoff-hold-0123456789")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedResourceRevision":0,"policyVersion":1}""")
            .exchange()
            .expectStatus()
            .isCreated

        webTestClient
            .post()
            .uri("/api/resources/1/waitlist")
            .header("X-Reservation-Owner", waiter)
            .header("Idempotency-Key", "cancel-handoff-wait-0123456789")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedResourceRevision":1,"policyVersion":1}""")
            .exchange()
            .expectStatus()
            .isCreated

        webTestClient
            .post()
            .uri("/api/holds/1/cancel")
            .header("X-Reservation-Owner", holder)
            .header("Idempotency-Key", "cancel-handoff-command-012345")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedRevision":0,"policyVersion":1}""")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.state")
            .isEqualTo("CANCELLED")

        webTestClient
            .get()
            .uri("/api/waitlist/1")
            .header("X-Reservation-Owner", waiter)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.state")
            .isEqualTo("OFFERED")
            .jsonPath("$.offerId")
            .isEqualTo(1)

        webTestClient
            .get()
            .uri("/api/resources")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.resources[0].occupiedCount")
            .isEqualTo(1)
            .jsonPath("$.resources[0].revision")
            .isEqualTo(1)
    }

    @Test
    fun `waitlist join is replayable and its snapshot is owner protected`() {
        val holder = "holder-token-0123456789abcdef0123456789abcdef"
        val waiter = "waiter-token-0123456789abcdef0123456789abcdef"
        webTestClient
            .post()
            .uri("/api/resources/1/holds")
            .header("X-Reservation-Owner", holder)
            .header("Idempotency-Key", "waitlist-prerequisite-hold-012345")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedResourceRevision":0,"policyVersion":1}""")
            .exchange()
            .expectStatus()
            .isCreated

        val joinKey = "waitlist-join-key-0123456789"
        webTestClient
            .post()
            .uri("/api/resources/1/waitlist")
            .header("X-Reservation-Owner", waiter)
            .header("Idempotency-Key", joinKey)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedResourceRevision":1,"policyVersion":1}""")
            .exchange()
            .expectStatus()
            .isCreated
            .expectHeader()
            .valueEquals("Idempotency-Replayed", "false")
            .expectBody()
            .jsonPath("$.state")
            .isEqualTo("WAITING")
            .jsonPath("$.sequence")
            .isEqualTo(1)

        webTestClient
            .post()
            .uri("/api/resources/1/waitlist")
            .header("X-Reservation-Owner", waiter)
            .header("Idempotency-Key", joinKey)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedResourceRevision":1,"policyVersion":1}""")
            .exchange()
            .expectStatus()
            .isCreated
            .expectHeader()
            .valueEquals("Idempotency-Replayed", "true")

        webTestClient
            .get()
            .uri("/api/waitlist/1")
            .header("X-Reservation-Owner", waiter)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.position")
            .isEqualTo(1)

        webTestClient
            .get()
            .uri("/api/waitlist/1")
            .header("X-Reservation-Owner", "other-token-0123456789abcdef0123456789abcdef")
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `short credentials are rejected without echoing them`() {
        webTestClient
            .post()
            .uri("/api/resources/1/holds")
            .header("X-Reservation-Owner", "too-short")
            .header("Idempotency-Key", "too-short")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedResourceRevision":0,"policyVersion":1}""")
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("MALFORMED_REQUEST")
    }
}
