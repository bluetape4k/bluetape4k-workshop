package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import org.junit.jupiter.api.Test
import java.util.UUID

internal class CustomerVoucherWebIntegrationTest : AbstractVoucherIntegrationTest() {
    @Test
    fun `redemption review fixture is consumed only by redemption`() {
        val tenant = randomIdentifier()
        val principal = "principal-redemption-review"
        val campaignId = createActiveCampaign(tenant)
        val allocated =
            customerPost(
                tenant,
                principal,
                "/api/v1/campaigns/$campaignId/claims",
                randomIdentifier(),
                mapOf("userRef" to principal),
            ).exchange().expectStatus().isCreated
                .expectBody(AllocationHttpResponse::class.java)
                .returnResult().responseBody!!

        operatorPost(
            tenant,
            "/operator/api/v1/fixtures/redemption-review/run",
            randomIdentifier(),
            mapOf("principalRef" to principal),
        ).exchange().expectStatus().isOk

        customerPost(
            tenant,
            principal,
            "/api/v1/claims/${allocated.claimId}/redeem",
            randomIdentifier(),
            mapOf(
                "code" to allocated.code,
                "expectedRevision" to 0,
                "redemptionReference" to randomIdentifier(),
            ),
        ).exchange().expectStatus().isAccepted
            .expectBody()
            .jsonPath("$.state").isEqualTo("REVIEW_REQUIRED")
            .jsonPath("$.revision").isEqualTo(1)
    }

    @Test
    fun `fixture replay does not rearm a consumed one-shot signal`() {
        val tenant = randomIdentifier()
        val principal = "principal-fixture-replay"
        val fixtureKey = randomIdentifier()
        operatorPost(
            tenant,
            "/operator/api/v1/fixtures/allocation-review/run",
            fixtureKey,
            mapOf("principalRef" to principal),
        ).exchange().expectStatus().isOk
            .expectHeader().valueEquals("Idempotency-Replayed", "false")

        val firstCampaign = createActiveCampaign(tenant)
        customerPost(
            tenant,
            principal,
            "/api/v1/campaigns/$firstCampaign/claims",
            randomIdentifier(),
            mapOf("userRef" to principal),
        ).exchange().expectStatus().isAccepted

        operatorPost(
            tenant,
            "/operator/api/v1/fixtures/allocation-review/run",
            fixtureKey,
            mapOf("principalRef" to principal),
        ).exchange().expectStatus().isOk
            .expectHeader().valueEquals("Idempotency-Replayed", "true")

        val secondCampaign = createActiveCampaign(tenant)
        customerPost(
            tenant,
            principal,
            "/api/v1/campaigns/$secondCampaign/claims",
            randomIdentifier(),
            mapOf("userRef" to principal),
        ).exchange().expectStatus().isCreated
    }

    @Test
    fun `delayed event fixture returns submitted principal and authoritative acceptance evidence`() {
        val tenant = randomIdentifier()
        val principal = "principal-delayed"
        val campaignId = createActiveCampaign(tenant)

        operatorPost(
            tenant,
            "/operator/api/v1/fixtures/delayed-duplicate-out-of-order/run",
            randomIdentifier(),
            mapOf("principalRef" to principal, "campaignId" to campaignId),
        ).exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.principalRef").isEqualTo(principal)
            .jsonPath("$.executionMode").isEqualTo("SERVER_EVENT")
            .jsonPath("$.evidence[0]").isEqualTo("APPLIED")
            .jsonPath("$.evidence[1]").isEqualTo("IGNORED")
            .jsonPath("$.evidence[2]").isEqualTo("CONFLICT")
    }

    @Test
    fun `operator can page open reviews and inspect reconciliation backlog`() {
        val tenant = randomIdentifier()
        val principal = "principal-operator-read"
        val campaignId = createActiveCampaign(tenant)
        operatorPost(
            tenant,
            "/operator/api/v1/fixtures/allocation-review/run",
            randomIdentifier(),
            mapOf("principalRef" to principal),
        ).exchange().expectStatus().isOk
        customerPost(
            tenant,
            principal,
            "/api/v1/campaigns/$campaignId/claims",
            randomIdentifier(),
            mapOf("userRef" to principal),
        ).exchange().expectStatus().isAccepted

        operatorGet(tenant, "/operator/api/v1/reviews?status=OPEN&limit=1")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.items[0].campaignId").isEqualTo(campaignId.toString())
            .jsonPath("$.items[0].status").isEqualTo("OPEN")
            .jsonPath("$.items[0].signalSummary").doesNotExist()

        operatorGet(tenant, "/operator/api/v1/reconciliation/backlog?limit=1")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.items").isArray
    }

    @Test
    fun `fixture reset is idempotent and clears tenant scoped one-shot signals`() {
        val tenant = randomIdentifier()
        val principal = "principal-fixture-reset"
        val deletedCampaignId = createActiveCampaign(tenant)
        operatorPost(
            tenant,
            "/operator/api/v1/fixtures/allocation-review/run",
            randomIdentifier(),
            mapOf("principalRef" to principal),
        ).exchange().expectStatus().isOk
        val resetKey = randomIdentifier()

        operatorPost(tenant, "/operator/api/v1/fixtures/reset", resetKey, emptyMap<String, String>())
            .exchange().expectStatus().isOk
            .expectHeader().valueEquals("Idempotency-Replayed", "false")
            .expectBody()
            .jsonPath("$.clearedSignals").isEqualTo(1)
            .jsonPath("$.deletedRows").value<Int> { check(it > 0) }
        operatorPost(tenant, "/operator/api/v1/fixtures/reset", resetKey, emptyMap<String, String>())
            .exchange().expectStatus().isOk
            .expectHeader().valueEquals("Idempotency-Replayed", "true")
            .expectBody()
            .jsonPath("$.clearedSignals").isEqualTo(1)
            .jsonPath("$.deletedRows").value<Int> { check(it > 0) }

        webTestClient.get().uri("/api/v1/campaigns/$deletedCampaignId")
            .header(TENANT_HEADER, tenant)
            .header(PRINCIPAL_HEADER, principal)
            .exchange().expectStatus().isNotFound

        val campaignId = createActiveCampaign(tenant)
        customerPost(
            tenant,
            principal,
            "/api/v1/campaigns/$campaignId/claims",
            randomIdentifier(),
            mapOf("userRef" to principal),
        ).exchange().expectStatus().isCreated
    }

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
