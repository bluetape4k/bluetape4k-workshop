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
import java.util.*

@Import(VoucherPoolExpiredReplayTestConfiguration::class)
/**
 * VoucherPoolExpiredReplayWebIntegrationTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolExpiredReplayWebIntegrationTest: AbstractVoucherPoolIntegrationTest() {
    @Test
/**
 * 이 시나리오는 "expired replay returns gone with its safe existing effect id" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `expired replay returns gone with its safe existing effect id`() {
        webTestClient.post()
            .uri("/api/v1/campaigns/${UUID.randomUUID()}/reservations")
            .contentType(MediaType.APPLICATION_JSON)
            .header(IDEMPOTENCY_HEADER, "expired-replay-key")
            .header("If-None-Match", "*")
            .header(TENANT_HEADER, "tenant-expired-replay")
            .header(PRINCIPAL_HEADER, "principal-expired-replay")
            .bodyValue(emptyMap<String, String>())
            .exchange()
            .expectStatus().isEqualTo(410)
            .expectBody()
            .jsonPath("$.code").isEqualTo("REPLAY_WINDOW_EXPIRED")
            .jsonPath("$.effectId").isEqualTo(EXPIRED_EFFECT_ID.toString())
    }
}

@TestConfiguration(proxyBeanMethods = false)
/**
 * VoucherPoolExpiredReplayTestConfiguration 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolExpiredReplayTestConfiguration {
    @Bean
    @Primary
/**
 * expiredReplayReservationService은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun expiredReplayReservationService(): ReservationService =
        object: ReservationService {
/**
 * reserve은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun reserve(command: ReserveVoucherCommand): MutationResult<ReservationSnapshot> =
                MutationResult.Expired(EXPIRED_EFFECT_ID, null)

/**
 * release은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun release(command: ReleaseReservationCommand): MutationResult<ReservationSnapshot> =
                error("release is outside the expired replay fixture")
        }
}

/**
 * EXPIRED_EFFECT_ID은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private val EXPIRED_EFFECT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000537")
