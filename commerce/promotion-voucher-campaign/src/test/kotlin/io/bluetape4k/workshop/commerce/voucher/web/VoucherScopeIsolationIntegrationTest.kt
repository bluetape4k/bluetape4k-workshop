package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody

internal class VoucherScopeIsolationIntegrationTest: AbstractVoucherIntegrationTest() {

    companion object: KLogging()

    @Test
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
