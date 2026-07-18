package io.bluetape4k.workshop.commerce.reservation

import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

@TestPropertySource(
    properties = [
        "reservation.redis.enabled=true",
        "reservation.redis.uri=redis://127.0.0.1:1",
    ],
)
internal class RedisUnavailableBootIntegrationTest : AbstractReservationIntegrationTest() {
    @Test
    fun `Redis connection failure does not prevent PostgreSQL authoritative API startup`() {
        webTestClient.get().uri("/api/resources").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.resources[0].code").isEqualTo("demo-room-utc")
    }
}
