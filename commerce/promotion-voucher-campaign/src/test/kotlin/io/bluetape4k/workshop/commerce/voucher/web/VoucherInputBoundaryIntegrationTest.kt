package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import org.junit.jupiter.api.Test

internal class VoucherInputBoundaryIntegrationTest: AbstractVoucherIntegrationTest() {

    companion object: KLogging()

    @Test
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
