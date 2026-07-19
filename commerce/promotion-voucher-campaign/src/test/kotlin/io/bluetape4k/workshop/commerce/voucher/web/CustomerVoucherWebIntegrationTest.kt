package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import org.junit.jupiter.api.Test
import java.util.UUID

internal class CustomerVoucherWebIntegrationTest : AbstractVoucherIntegrationTest() {
    @Test
    fun `allocation replays the same one-time code while GET stays safe and tenant scoped`() {
        val tenant = randomIdentifier()
        val principal = "principal-a"
        val campaignId = createActiveCampaign(tenant)
        val key = randomIdentifier()
        val first =
            customerPost(
                tenant,
                principal,
                "/api/v1/campaigns/$campaignId/claims",
                key,
                mapOf("userRef" to principal),
            ).exchange().expectStatus().isCreated
                .expectHeader().valueEquals("Idempotency-Replayed", "false")
                .expectBody()
                .jsonPath("$.claimId").value<String> { UUID.fromString(it) }
                .jsonPath("$.code").isNotEmpty
                .returnResult()
        val body = String(first.responseBody!!)
        val claimId = Regex("\\\"claimId\\\":\\\"([^\\\"]+)\\\"").find(body)!!.groupValues[1]
        val code = Regex("\\\"code\\\":\\\"([^\\\"]+)\\\"").find(body)!!.groupValues[1]

        customerPost(
            tenant,
            principal,
            "/api/v1/campaigns/$campaignId/claims",
            key,
            mapOf("userRef" to principal),
        ).exchange().expectStatus().isCreated
            .expectHeader().valueEquals("Idempotency-Replayed", "true")
            .expectBody()
            .jsonPath("$.claimId").isEqualTo(claimId)
            .jsonPath("$.code").isEqualTo(code)

        repeat(2) {
            webTestClient.get().uri("/api/v1/claims/$claimId")
                .header(TENANT_HEADER, tenant)
                .header(PRINCIPAL_HEADER, principal)
                .exchange().expectStatus().isOk
                .expectBody()
                .jsonPath("$.claimId").isEqualTo(claimId)
                .jsonPath("$.code").doesNotExist()
        }
        webTestClient.get().uri("/api/v1/claims/$claimId")
            .header(TENANT_HEADER, "other-$tenant")
            .header(PRINCIPAL_HEADER, principal)
            .exchange().expectStatus().isNotFound
            .expectBody().jsonPath("$.code").isEqualTo("CLAIM_NOT_FOUND")
    }

    @Test
    fun `unknown customer request property is rejected before allocation`() {
        val tenant = randomIdentifier()
        val campaignId = createActiveCampaign(tenant)

        customerPost(
            tenant,
            "principal-a",
            "/api/v1/campaigns/$campaignId/claims",
            randomIdentifier(),
            mapOf("userRef" to "principal-a", "riskSignal" to "REVIEW"),
        ).exchange().expectStatus().isBadRequest
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST")
    }

    @Test
    fun `release is exactly replayed and the same raw key cannot change its fingerprint`() {
        val tenant = randomIdentifier()
        val principal = "principal-release"
        val campaignId = createActiveCampaign(tenant)
        val claim =
            customerPost(
                tenant,
                principal,
                "/api/v1/campaigns/$campaignId/claims",
                randomIdentifier(),
                mapOf("userRef" to principal),
            ).exchange().expectStatus().isCreated
                .expectBody(AllocationHttpResponse::class.java)
                .returnResult().responseBody!!
        val releaseKey = randomIdentifier()

        customerPost(
            tenant,
            principal,
            "/api/v1/claims/${claim.claimId}/release",
            releaseKey,
            mapOf("expectedRevision" to 0),
        ).exchange().expectStatus().isOk
            .expectHeader().valueEquals("Idempotency-Replayed", "false")
            .expectBody()
            .jsonPath("$.state").isEqualTo("RELEASED")
            .jsonPath("$.revision").isEqualTo(1)

        customerPost(
            tenant,
            principal,
            "/api/v1/claims/${claim.claimId}/release",
            releaseKey,
            mapOf("expectedRevision" to 0),
        ).exchange().expectStatus().isOk
            .expectHeader().valueEquals("Idempotency-Replayed", "true")
            .expectBody().jsonPath("$.state").isEqualTo("RELEASED")

        customerPost(
            tenant,
            principal,
            "/api/v1/claims/${claim.claimId}/release",
            releaseKey,
            mapOf("expectedRevision" to 1),
        ).exchange().expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("IDEMPOTENCY_FINGERPRINT_CONFLICT")
    }

    @Test
    fun `claim ownership is hidden across principals in the same tenant`() {
        val tenant = randomIdentifier()
        val campaignId = createActiveCampaign(tenant)
        val claim =
            customerPost(
                tenant,
                "principal-owner",
                "/api/v1/campaigns/$campaignId/claims",
                randomIdentifier(),
                mapOf("userRef" to "principal-owner"),
            ).exchange().expectStatus().isCreated
                .expectBody(AllocationHttpResponse::class.java)
                .returnResult().responseBody!!

        webTestClient.get().uri("/api/v1/claims/${claim.claimId}")
            .header(TENANT_HEADER, tenant)
            .header(PRINCIPAL_HEADER, "principal-attacker")
            .exchange().expectStatus().isNotFound
            .expectBody().jsonPath("$.code").isEqualTo("CLAIM_NOT_FOUND")
    }

    @Test
    fun `review approved code acknowledgement replays only for its original key`() {
        val tenant = randomIdentifier()
        val principal = "principal-review"
        val campaignId = createActiveCampaign(tenant)
        operatorPost(
            tenant,
            "/operator/api/v1/fixtures/allocation-review/run",
            randomIdentifier(),
            mapOf("principalRef" to principal),
        ).exchange().expectStatus().isOk

        val allocationKey = randomIdentifier()
        val pending =
            customerPost(
                tenant,
                principal,
                "/api/v1/campaigns/$campaignId/claims",
                allocationKey,
                mapOf("userRef" to principal),
            ).exchange().expectStatus().isAccepted
                .expectBody(AllocationHttpResponse::class.java)
                .returnResult().responseBody!!
        val reviewId = checkNotNull(pending.reviewId)
        check(pending.code == null)

        operatorPost(
            tenant,
            "/operator/api/v1/reviews/$reviewId/approve",
            randomIdentifier(),
            mapOf(
                "campaignId" to campaignId,
                "claimId" to pending.claimId,
                "expectedReviewRevision" to 0,
                "expectedClaimRevision" to 0,
            ),
        ).exchange().expectStatus().isOk

        customerPost(
            tenant,
            principal,
            "/api/v1/campaigns/$campaignId/claims",
            allocationKey,
            mapOf("userRef" to principal),
        ).exchange().expectStatus().isAccepted
            .expectHeader().valueEquals("Idempotency-Replayed", "true")
            .expectBody()
            .jsonPath("$.state").isEqualTo("REVIEW_REQUIRED")
            .jsonPath("$.revision").isEqualTo(0)
            .jsonPath("$.reviewId").isEqualTo(reviewId)
            .jsonPath("$.code").doesNotExist()

        val acknowledgementKey = randomIdentifier()
        val first =
            customerPost(
                tenant,
                principal,
                "/api/v1/claims/${pending.claimId}/code-acknowledgements",
                acknowledgementKey,
                mapOf("expectedRevision" to 1),
            ).exchange().expectStatus().isOk
                .expectHeader().valueEquals("Idempotency-Replayed", "false")
                .expectBody(VoucherCodeHttpResponse::class.java)
                .returnResult().responseBody!!

        customerPost(
            tenant,
            principal,
            "/api/v1/claims/${pending.claimId}/code-acknowledgements",
            acknowledgementKey,
            mapOf("expectedRevision" to 1),
        ).exchange().expectStatus().isOk
            .expectHeader().valueEquals("Idempotency-Replayed", "true")
            .expectBody().jsonPath("$.code").isEqualTo(first.code)

        customerPost(
            tenant,
            principal,
            "/api/v1/claims/${pending.claimId}/code-acknowledgements",
            randomIdentifier(),
            mapOf("expectedRevision" to 2),
        ).exchange().expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("CODE_ALREADY_ACKNOWLEDGED")

        webTestClient.get().uri("/api/v1/claims/${pending.claimId}")
            .header(TENANT_HEADER, tenant)
            .header(PRINCIPAL_HEADER, principal)
            .exchange().expectStatus().isOk
            .expectBody().jsonPath("$.code").doesNotExist()
    }
}
