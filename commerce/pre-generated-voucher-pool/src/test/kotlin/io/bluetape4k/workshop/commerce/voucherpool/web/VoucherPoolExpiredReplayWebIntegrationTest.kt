package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.workshop.commerce.voucherpool.AbstractVoucherPoolIntegrationTest
import io.bluetape4k.workshop.commerce.voucherpool.application.MutationResult
import io.bluetape4k.workshop.commerce.voucherpool.application.ReleaseReservationCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.ReservationService
import io.bluetape4k.workshop.commerce.voucherpool.application.ReservationSnapshot
import io.bluetape4k.workshop.commerce.voucherpool.application.ReserveVoucherCommand
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import java.util.UUID

@Import(VoucherPoolExpiredReplayTestConfiguration::class)
internal class VoucherPoolExpiredReplayWebIntegrationTest : AbstractVoucherPoolIntegrationTest() {
    @Test
    fun `expired replay returns gone with its safe existing effect id`() {
        webTestClient.post().uri("/api/v1/campaigns/${UUID.randomUUID()}/reservations")
            .contentType(MediaType.APPLICATION_JSON)
            .header(IDEMPOTENCY_HEADER, "expired-replay-key")
            .header("If-None-Match", "*")
            .header(TENANT_HEADER, "tenant-expired-replay")
            .header(PRINCIPAL_HEADER, "principal-expired-replay")
            .bodyValue(emptyMap<String, String>())
            .exchange().expectStatus().isEqualTo(410)
            .expectBody()
            .jsonPath("$.code").isEqualTo("REPLAY_WINDOW_EXPIRED")
            .jsonPath("$.effectId").isEqualTo(EXPIRED_EFFECT_ID.toString())
    }
}

@TestConfiguration(proxyBeanMethods = false)
internal class VoucherPoolExpiredReplayTestConfiguration {
    @Bean
    @Primary
    fun expiredReplayReservationService(): ReservationService =
        object : ReservationService {
            override fun reserve(command: ReserveVoucherCommand): MutationResult<ReservationSnapshot> =
                MutationResult.Expired(EXPIRED_EFFECT_ID, null)

            override fun release(command: ReleaseReservationCommand): MutationResult<ReservationSnapshot> =
                error("release is outside the expired replay fixture")
        }
}

private val EXPIRED_EFFECT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000537")
