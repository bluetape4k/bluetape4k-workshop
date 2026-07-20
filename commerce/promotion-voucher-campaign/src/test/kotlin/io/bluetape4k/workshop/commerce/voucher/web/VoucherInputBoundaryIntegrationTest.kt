package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import org.junit.jupiter.api.Test

internal class VoucherInputBoundaryIntegrationTest : AbstractVoucherIntegrationTest() {
    @Test
    fun `ASCII header limits accept N and reject N plus one before allocation`() {
        val tenant = "t".repeat(64)
        val principal = "p".repeat(64)
        val campaignId = createActiveCampaign(tenant)

        customerPost(
            tenant,
            principal,
            "/api/v1/campaigns/$campaignId/claims",
            "k".repeat(65),
            mapOf("userRef" to principal),
        ).exchange().expectStatus().isBadRequest
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST")

        customerPost(
            tenant,
            principal,
            "/api/v1/campaigns/$campaignId/claims",
            "k".repeat(64),
            mapOf("userRef" to principal),
        ).exchange().expectStatus().isCreated
    }

    @Test
    fun `oversized and whitespace characters are rejected from security scope headers`() {
        val tenant = randomIdentifier()
        val campaignId = createActiveCampaign(tenant)

        customerPost(
            "t".repeat(65),
            "principal-a",
            "/api/v1/campaigns/$campaignId/claims",
            randomIdentifier(),
            mapOf("userRef" to "principal-a"),
        ).exchange().expectStatus().isBadRequest
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST")

        customerPost(
            tenant,
            "principal-a",
            "/api/v1/campaigns/$campaignId/claims",
            "invalid key",
            mapOf("userRef" to "principal-a"),
        ).exchange().expectStatus().isBadRequest
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST")
    }
}
