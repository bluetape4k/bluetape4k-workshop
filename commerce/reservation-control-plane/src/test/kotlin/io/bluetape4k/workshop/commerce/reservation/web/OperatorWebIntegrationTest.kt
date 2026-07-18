package io.bluetape4k.workshop.commerce.reservation.web

import io.bluetape4k.workshop.commerce.reservation.AbstractReservationIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["reservation.operator.enabled=true"])
internal class OperatorWebIntegrationTest : AbstractReservationIntegrationTest() {
    @Test
    fun `operator key protects idempotent force release and bounded manual sweep`() {
        val owner = "operator-fixture-owner-0123456789abcdef0123456789abcdef"
        webTestClient.post().uri("/api/resources/1/holds")
            .header("X-Reservation-Owner", owner)
            .header("Idempotency-Key", "operator-fixture-hold-0123456789")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedResourceRevision":0,"policyVersion":1}""")
            .exchange()
            .expectStatus().isCreated

        val waiter = "operator-waiter-0123456789abcdef0123456789abcdef"
        webTestClient.post().uri("/api/resources/1/waitlist")
            .header("X-Reservation-Owner", waiter)
            .header("Idempotency-Key", "operator-waitlist-key-0123456789")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedResourceRevision":1,"policyVersion":1}""")
            .exchange()
            .expectStatus().isCreated

        val releaseBody = """{"expectedRevision":0,"reasonCode":"DEMO_RECOVERY"}"""
        webTestClient.post().uri("/api/operator/holds/1/force-release")
            .header("Idempotency-Key", "operator-release-key-0123456789")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(releaseBody)
            .exchange()
            .expectStatus().isForbidden

        webTestClient.post().uri("/api/operator/holds/1/force-release")
            .header("X-Operator-Key", OPERATOR_KEY)
            .header("Idempotency-Key", "operator-release-key-0123456789")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(releaseBody)
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("Idempotency-Replayed", "false")
            .expectBody()
            .jsonPath("$.state").isEqualTo("RELEASED_BY_OPERATOR")

        webTestClient.get().uri("/api/waitlist/1")
            .header("X-Reservation-Owner", waiter)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.state").isEqualTo("OFFERED")
            .jsonPath("$.offerId").isEqualTo(1)

        webTestClient.get().uri("/api/resources").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.resources[0].occupiedCount").isEqualTo(1)

        webTestClient.post().uri("/api/operator/holds/1/force-release")
            .header("X-Operator-Key", OPERATOR_KEY)
            .header("Idempotency-Key", "operator-release-key-0123456789")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(releaseBody)
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("Idempotency-Replayed", "true")

        webTestClient.post().uri("/api/operator/sweep")
            .header("X-Operator-Key", OPERATOR_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"maxResources":32}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.scannedResources").isEqualTo(0)
    }

    companion object {
        private const val OPERATOR_KEY = "local-demo-operator-key-32-bytes-minimum"
    }
}
