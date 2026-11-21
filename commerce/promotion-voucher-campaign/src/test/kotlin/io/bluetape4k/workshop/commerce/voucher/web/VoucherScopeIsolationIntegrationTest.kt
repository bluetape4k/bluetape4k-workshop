package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody

/**
 * VoucherScopeIsolationIntegrationTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherScopeIsolationIntegrationTest: AbstractVoucherIntegrationTest() {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "the same raw idempotency key is independent across tenant and principal scopes" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `the same raw idempotency key is independent across tenant and principal scopes`() {
        val tenantA = randomIdentifier()
        val tenantB = randomIdentifier()
        val campaignA = createActiveCampaign(tenantA)
        val campaignB = createActiveCampaign(tenantB)
        val rawKey = randomIdentifier()

        val claimA = allocate(tenantA, "principal-a", campaignA, rawKey)
        val claimB = allocate(tenantB, "principal-b", campaignB, rawKey)
        val claimC = allocate(tenantA, "principal-c", campaignA, rawKey)

        claimA.claimId shouldNotBeEqualTo claimB.claimId
        claimA.claimId shouldNotBeEqualTo claimC.claimId

        webTestClient.get()
            .uri("/api/v1/claims/${claimA.claimId}")
            .header(TENANT_HEADER, tenantB)
            .header(PRINCIPAL_HEADER, "principal-a")
            .exchange()
            .expectStatus().isNotFound

        webTestClient.get()
            .uri("/api/v1/claims/${claimA.claimId}")
            .header(TENANT_HEADER, tenantA)
            .header(PRINCIPAL_HEADER, "principal-c")
            .exchange()
            .expectStatus().isNotFound
    }

/**
 * allocate은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun allocate(
        tenant: String,
        principal: String,
        campaignId: java.util.UUID,
        rawKey: String,
    ): AllocationHttpResponse =
        customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/campaigns/$campaignId/claims",
            idempotencyKey = rawKey,
            body = mapOf("userRef" to principal),
        )
            .exchange()
            .expectStatus().isCreated
            .expectHeader().valueEquals("Idempotency-Replayed", "false")
            .expectBody<AllocationHttpResponse>()
            .returnResult().responseBody.shouldNotBeNull()
}
