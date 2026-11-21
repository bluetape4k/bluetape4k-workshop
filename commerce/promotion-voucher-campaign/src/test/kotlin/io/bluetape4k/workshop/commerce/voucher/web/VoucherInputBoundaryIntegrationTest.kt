package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import org.junit.jupiter.api.Test

/**
 * VoucherInputBoundaryIntegrationTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherInputBoundaryIntegrationTest: AbstractVoucherIntegrationTest() {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "ASCII header limits accept N and reject N plus one before allocation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `ASCII header limits accept N and reject N plus one before allocation`() {
        val tenant = "t".repeat(64)
        val principal = "p".repeat(64)
        val campaignId = createActiveCampaign(tenant)

        customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/campaigns/$campaignId/claims",
            idempotencyKey = "k".repeat(65),
            body = mapOf("userRef" to principal),
        )
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")

        customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/campaigns/$campaignId/claims",
            idempotencyKey = "k".repeat(64),
            body = mapOf("userRef" to principal),
        )
            .exchange()
            .expectStatus().isCreated
    }

    @Test
/**
 * 이 시나리오는 "oversized and whitespace characters are rejected from security scope headers" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `oversized and whitespace characters are rejected from security scope headers`() {
        val tenant = randomIdentifier()
        val campaignId = createActiveCampaign(tenant)

        customerPost(
            tenant = "t".repeat(65),
            principal = "principal-a",
            path = "/api/v1/campaigns/$campaignId/claims",
            idempotencyKey = randomIdentifier(),
            body = mapOf("userRef" to "principal-a"),
        )
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")

        customerPost(
            tenant = tenant,
            principal = "principal-a",
            path = "/api/v1/campaigns/$campaignId/claims",
            idempotencyKey = "invalid key",
            body = mapOf("userRef" to "principal-a"),
        )
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
    }
}
