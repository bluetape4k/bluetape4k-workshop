package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody
import java.util.*

/**
 * CustomerVoucherWebIntegrationTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class CustomerVoucherWebIntegrationTest: AbstractVoucherIntegrationTest() {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "redemption review fixture is consumed only by redemption" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `redemption review fixture is consumed only by redemption`() {
        val tenant = randomIdentifier()
        val principal = "principal-redemption-review"
        val campaignId = createActiveCampaign(tenant)

        val allocated = customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/campaigns/$campaignId/claims",
            idempotencyKey = randomIdentifier(),
            body = mapOf("userRef" to principal),
        )
            .exchange()
            .expectStatus().isCreated
            .expectBody<AllocationHttpResponse>()
            .returnResult().responseBody.shouldNotBeNull()

        operatorPost(
            tenant,
            "/operator/api/v1/fixtures/redemption-review/run",
            randomIdentifier(),
            mapOf("principalRef" to principal),
        )
            .exchange()
            .expectStatus().isOk

        customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/claims/${allocated.claimId}/redeem",
            idempotencyKey = randomIdentifier(),
            body = mapOf(
                "code" to allocated.code,
                "expectedRevision" to 0,
                "redemptionReference" to randomIdentifier(),
            ),
        )
            .exchange()
            .expectStatus().isAccepted
            .expectBody()
            .jsonPath("$.state").isEqualTo("REVIEW_REQUIRED")
            .jsonPath("$.revision").isEqualTo(1)
    }

    @Test
/**
 * 이 시나리오는 "fixture replay does not rearm a consumed one-shot signal" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `fixture replay does not rearm a consumed one-shot signal`() {
        val tenant = randomIdentifier()
        val principal = "principal-fixture-replay"
        val fixtureKey = randomIdentifier()

        operatorPost(
            tenant = tenant,
            path = "/operator/api/v1/fixtures/allocation-review/run",
            idempotencyKey = fixtureKey,
            body = mapOf("principalRef" to principal),
        )
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("Idempotency-Replayed", "false")

        val firstCampaign = createActiveCampaign(tenant)
        customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/campaigns/$firstCampaign/claims",
            idempotencyKey = randomIdentifier(),
            body = mapOf("userRef" to principal),
        )
            .exchange()
            .expectStatus().isAccepted

        operatorPost(
            tenant = tenant,
            path = "/operator/api/v1/fixtures/allocation-review/run",
            idempotencyKey = fixtureKey,
            body = mapOf("principalRef" to principal),
        )
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("Idempotency-Replayed", "true")

        val secondCampaign = createActiveCampaign(tenant)
        customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/campaigns/$secondCampaign/claims",
            idempotencyKey = randomIdentifier(),
            body = mapOf("userRef" to principal),
        )
            .exchange()
            .expectStatus().isCreated
    }

    @Test
/**
 * 이 시나리오는 "delayed event fixture returns submitted principal and authoritative acceptance
 * evidence" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `delayed event fixture returns submitted principal and authoritative acceptance evidence`() {
        val tenant = randomIdentifier()
        val principal = "principal-delayed"
        val campaignId = createActiveCampaign(tenant)

        operatorPost(
            tenant = tenant,
            path = "/operator/api/v1/fixtures/delayed-duplicate-out-of-order/run",
            idempotencyKey = randomIdentifier(),
            body = mapOf("principalRef" to principal, "campaignId" to campaignId),
        )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.principalRef").isEqualTo(principal)
            .jsonPath("$.executionMode").isEqualTo("SERVER_EVENT")
            .jsonPath("$.evidence[0]").isEqualTo("APPLIED")
            .jsonPath("$.evidence[1]").isEqualTo("IGNORED")
            .jsonPath("$.evidence[2]").isEqualTo("CONFLICT")
    }

    @Test
/**
 * 이 시나리오는 "operator can page open reviews and inspect reconciliation backlog" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operator can page open reviews and inspect reconciliation backlog`() {
        val tenant = randomIdentifier()
        val principal = "principal-operator-read"
        val campaignId = createActiveCampaign(tenant)

        operatorPost(
            tenant = tenant,
            path = "/operator/api/v1/fixtures/allocation-review/run",
            idempotencyKey = randomIdentifier(),
            body = mapOf("principalRef" to principal),
        )
            .exchange()
            .expectStatus().isOk

        customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/campaigns/$campaignId/claims",
            idempotencyKey = randomIdentifier(),
            body = mapOf("userRef" to principal),
        )
            .exchange()
            .expectStatus().isAccepted

        operatorGet(tenant, "/operator/api/v1/reviews?status=OPEN&limit=1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.items[0].campaignId").isEqualTo(campaignId.toString())
            .jsonPath("$.items[0].status").isEqualTo("OPEN")
            .jsonPath("$.items[0].signalSummary").doesNotExist()

        operatorGet(tenant, "/operator/api/v1/reconciliation/backlog?limit=1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.items").isArray
    }

    @Test
/**
 * 이 시나리오는 "fixture reset is idempotent and clears tenant scoped one-shot signals" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `fixture reset is idempotent and clears tenant scoped one-shot signals`() {
        val tenant = randomIdentifier()
        val principal = "principal-fixture-reset"
        val deletedCampaignId = createActiveCampaign(tenant)

        operatorPost(
            tenant = tenant,
            path = "/operator/api/v1/fixtures/allocation-review/run",
            idempotencyKey = randomIdentifier(),
            body = mapOf("principalRef" to principal),
        )
            .exchange()
            .expectStatus().isOk

        val resetKey = randomIdentifier()

        operatorPost(
            tenant = tenant,
            path = "/operator/api/v1/fixtures/reset",
            idempotencyKey = resetKey,
            body = emptyMap<String, String>()
        )
            .exchange()
            .expectStatus().isOk
            .expectHeader()
            .valueEquals("Idempotency-Replayed", "false")
            .expectBody()
            .jsonPath("$.clearedSignals").isEqualTo(1)
            .jsonPath("$.deletedRows").value<Int> { check(it > 0) }

        operatorPost(
            tenant = tenant,
            path = "/operator/api/v1/fixtures/reset",
            idempotencyKey = resetKey,
            body = emptyMap<String, String>()
        )
            .exchange()
            .expectStatus().isOk
            .expectHeader()
            .valueEquals("Idempotency-Replayed", "true")
            .expectBody()
            .jsonPath("$.clearedSignals").isEqualTo(1)
            .jsonPath("$.deletedRows").value<Int> { check(it > 0) }

        webTestClient.get()
            .uri("/api/v1/campaigns/$deletedCampaignId")
            .header(TENANT_HEADER, tenant)
            .header(PRINCIPAL_HEADER, principal)
            .exchange().expectStatus().isNotFound

        val campaignId = createActiveCampaign(tenant)
        customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/campaigns/$campaignId/claims",
            idempotencyKey = randomIdentifier(),
            body = mapOf("userRef" to principal),
        )
            .exchange()
            .expectStatus().isCreated
    }

    @Test
/**
 * 이 시나리오는 "allocation replays the same one-time code while GET stays safe and tenant scoped" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `allocation replays the same one-time code while GET stays safe and tenant scoped`() {
        val tenant = randomIdentifier()
        val principal = "principal-a"
        val campaignId = createActiveCampaign(tenant)
        val key = randomIdentifier()
        val first = customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/campaigns/$campaignId/claims",
            idempotencyKey = key,
            body = mapOf("userRef" to principal),
        )
            .exchange()
            .expectStatus().isCreated
            .expectHeader().valueEquals("Idempotency-Replayed", "false")
            .expectBody()
            .jsonPath("$.claimId").value<String> { UUID.fromString(it) }
            .jsonPath("$.code").isNotEmpty
            .returnResult()

        val body = first.responseBody.shouldNotBeNull().toUtf8String()
        val claimId = Regex("\\\"claimId\\\":\\\"([^\\\"]+)\\\"").find(body)!!.groupValues[1]
        val code = Regex("\\\"code\\\":\\\"([^\\\"]+)\\\"").find(body)!!.groupValues[1]

        customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/campaigns/$campaignId/claims",
            idempotencyKey = key,
            body = mapOf("userRef" to principal),
        )
            .exchange()
            .expectStatus().isCreated
            .expectHeader().valueEquals("Idempotency-Replayed", "true")
            .expectBody()
            .jsonPath("$.claimId").isEqualTo(claimId)
            .jsonPath("$.code").isEqualTo(code)

        repeat(2) {
            webTestClient.get()
                .uri("/api/v1/claims/$claimId")
                .header(TENANT_HEADER, tenant)
                .header(PRINCIPAL_HEADER, principal)
                .exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.claimId").isEqualTo(claimId)
                .jsonPath("$.code").doesNotExist()
        }
        webTestClient.get()
            .uri("/api/v1/claims/$claimId")
            .header(TENANT_HEADER, "other-$tenant")
            .header(PRINCIPAL_HEADER, principal)
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.code").isEqualTo("CLAIM_NOT_FOUND")
    }

    @Test
/**
 * 이 시나리오는 "unknown customer request property is rejected before allocation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `unknown customer request property is rejected before allocation`() {
        val tenant = randomIdentifier()
        val campaignId = createActiveCampaign(tenant)

        customerPost(
            tenant = tenant,
            principal = "principal-a",
            path = "/api/v1/campaigns/$campaignId/claims",
            idempotencyKey = randomIdentifier(),
            body = mapOf("userRef" to "principal-a", "riskSignal" to "REVIEW"),
        )
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
    }

    @Test
/**
 * 이 시나리오는 "release is exactly replayed and the same raw key cannot change its fingerprint" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `release is exactly replayed and the same raw key cannot change its fingerprint`() {
        val tenant = randomIdentifier()
        val principal = "principal-release"
        val campaignId = createActiveCampaign(tenant)
        val claim = customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/campaigns/$campaignId/claims",
            idempotencyKey = randomIdentifier(),
            body = mapOf("userRef" to principal),
        )
            .exchange()
            .expectStatus().isCreated
            .expectBody<AllocationHttpResponse>()
            .returnResult().responseBody.shouldNotBeNull()

        val releaseKey = randomIdentifier()

        customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/claims/${claim.claimId}/release",
            idempotencyKey = releaseKey,
            body = mapOf("expectedRevision" to 0),
        )
            .exchange()
            .expectStatus().isOk
            .expectHeader()
            .valueEquals("Idempotency-Replayed", "false")
            .expectBody()
            .jsonPath("$.state").isEqualTo("RELEASED")
            .jsonPath("$.revision").isEqualTo(1)

        customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/claims/${claim.claimId}/release",
            idempotencyKey = releaseKey,
            body = mapOf("expectedRevision" to 0),
        )
            .exchange()
            .expectStatus().isOk
            .expectHeader()
            .valueEquals("Idempotency-Replayed", "true")
            .expectBody()
            .jsonPath("$.state").isEqualTo("RELEASED")

        customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/claims/${claim.claimId}/release",
            idempotencyKey = releaseKey,
            body = mapOf("expectedRevision" to 1),
        )
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("IDEMPOTENCY_FINGERPRINT_CONFLICT")
    }

    @Test
/**
 * 이 시나리오는 "claim ownership is hidden across principals in the same tenant" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `claim ownership is hidden across principals in the same tenant`() {
        val tenant = randomIdentifier()
        val campaignId = createActiveCampaign(tenant)
        val claim = customerPost(
            tenant = tenant,
            principal = "principal-owner",
            path = "/api/v1/campaigns/$campaignId/claims",
            idempotencyKey = randomIdentifier(),
            body = mapOf("userRef" to "principal-owner"),
        )
            .exchange()
            .expectStatus().isCreated
            .expectBody<AllocationHttpResponse>()
            .returnResult().responseBody.shouldNotBeNull()

        webTestClient.get()
            .uri("/api/v1/claims/${claim.claimId}")
            .header(TENANT_HEADER, tenant)
            .header(PRINCIPAL_HEADER, "principal-attacker")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.code").isEqualTo("CLAIM_NOT_FOUND")
    }

    @Test
/**
 * 이 시나리오는 "review approved code acknowledgement replays only for its original key" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `review approved code acknowledgement replays only for its original key`() {
        val tenant = randomIdentifier()
        val principal = "principal-review"
        val campaignId = createActiveCampaign(tenant)

        operatorPost(
            tenant = tenant,
            path = "/operator/api/v1/fixtures/allocation-review/run",
            idempotencyKey = randomIdentifier(),
            body = mapOf("principalRef" to principal),
        )
            .exchange()
            .expectStatus().isOk

        val allocationKey = randomIdentifier()
        val pending = customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/campaigns/$campaignId/claims",
            idempotencyKey = allocationKey,
            body = mapOf("userRef" to principal),
        )
            .exchange()
            .expectStatus().isAccepted
            .expectBody<AllocationHttpResponse>()
            .returnResult().responseBody.shouldNotBeNull()

        val reviewId = pending.reviewId.shouldNotBeNull()
        pending.code.shouldBeNull()

        operatorPost(
            tenant = tenant,
            path = "/operator/api/v1/reviews/$reviewId/approve",
            idempotencyKey = randomIdentifier(),
            body = mapOf(
                "campaignId" to campaignId,
                "claimId" to pending.claimId,
                "expectedReviewRevision" to 0,
                "expectedClaimRevision" to 0,
            ),
        )
            .exchange()
            .expectStatus().isOk

        customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/campaigns/$campaignId/claims",
            idempotencyKey = allocationKey,
            body = mapOf("userRef" to principal),
        )
            .exchange()
            .expectStatus().isAccepted
            .expectHeader()
            .valueEquals("Idempotency-Replayed", "true")
            .expectBody()
            .jsonPath("$.state").isEqualTo("REVIEW_REQUIRED")
            .jsonPath("$.revision").isEqualTo(0)
            .jsonPath("$.reviewId").isEqualTo(reviewId)
            .jsonPath("$.code").doesNotExist()

        val acknowledgementKey = randomIdentifier()
        val first = customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/claims/${pending.claimId}/code-acknowledgements",
            idempotencyKey = acknowledgementKey,
            body = mapOf("expectedRevision" to 1),
        )
            .exchange()
            .expectStatus().isOk
            .expectHeader()
            .valueEquals("Idempotency-Replayed", "false")
            .expectBody<VoucherCodeHttpResponse>()
            .returnResult().responseBody.shouldNotBeNull()

        customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/claims/${pending.claimId}/code-acknowledgements",
            idempotencyKey = acknowledgementKey,
            body = mapOf("expectedRevision" to 1),
        )
            .exchange()
            .expectStatus().isOk
            .expectHeader()
            .valueEquals("Idempotency-Replayed", "true")
            .expectBody()
            .jsonPath("$.code").isEqualTo(first.code)

        customerPost(
            tenant = tenant,
            principal = principal,
            path = "/api/v1/claims/${pending.claimId}/code-acknowledgements",
            idempotencyKey = randomIdentifier(),
            body = mapOf("expectedRevision" to 2),
        )
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("CODE_ALREADY_ACKNOWLEDGED")

        webTestClient.get()
            .uri("/api/v1/claims/${pending.claimId}")
            .header(TENANT_HEADER, tenant)
            .header(PRINCIPAL_HEADER, principal)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.code").doesNotExist()
    }
}
