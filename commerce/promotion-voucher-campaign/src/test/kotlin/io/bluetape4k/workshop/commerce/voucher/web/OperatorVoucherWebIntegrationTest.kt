package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import io.bluetape4k.idgenerators.uuid.Uuid
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class OperatorVoucherWebIntegrationTest : AbstractVoucherIntegrationTest() {
    @Test
    fun `campaign create and activate enforce preconditions and replay`() {
        val tenant = randomIdentifier()
        val campaignId = Uuid.V7.nextId()
        val body =
            mapOf(
                "campaignId" to campaignId,
                "startsAt" to Instant.now().minusSeconds(60),
                "endsAt" to Instant.now().plusSeconds(3600),
                "capacity" to 10,
                "perUserLimit" to 1,
                "redemptionTtlSeconds" to 600,
            )
        val key = randomIdentifier()

        operatorPost(tenant, "/operator/api/v1/campaigns", key, body) {
            it.header("If-None-Match", "*")
        }.exchange().expectStatus().isCreated
            .expectHeader().valueEquals("Idempotency-Replayed", "false")
            .expectHeader().valueEquals("Location", "/api/v1/campaigns/$campaignId")

        operatorPost(tenant, "/operator/api/v1/campaigns", key, body) {
            it.header("If-None-Match", "*")
        }.exchange().expectStatus().isCreated
            .expectHeader().valueEquals("Idempotency-Replayed", "true")

        operatorPost(
            tenant,
            "/operator/api/v1/campaigns/$campaignId/activate",
            randomIdentifier(),
            mapOf("expectedRevision" to 0),
        ).exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.state").isEqualTo("ACTIVE")
            .jsonPath("$.revision").isEqualTo(1)

        operatorPost(tenant, "/operator/api/v1/campaigns", key, body) {
            it.header("If-None-Match", "*")
        }.exchange().expectStatus().isCreated
            .expectHeader().valueEquals("Idempotency-Replayed", "true")
            .expectBody()
            .jsonPath("$.state").isEqualTo("DRAFT")
            .jsonPath("$.revision").isEqualTo(0)
    }

    @Test
    fun `stale campaign revision is a closed precondition failure`() {
        val tenant = randomIdentifier()
        val campaignId = createActiveCampaign(tenant)
        val key = randomIdentifier()

        operatorPost(
            tenant,
            "/operator/api/v1/campaigns/$campaignId/pause",
            key,
            mapOf("expectedRevision" to 0),
        ).exchange().expectStatus().isEqualTo(412)
            .expectHeader().valueEquals("Idempotency-Replayed", "false")
            .expectBody().jsonPath("$.code").isEqualTo("STALE_REVISION")

        operatorPost(
            tenant,
            "/operator/api/v1/campaigns/$campaignId/pause",
            key,
            mapOf("expectedRevision" to 0),
        ).exchange().expectStatus().isEqualTo(412)
            .expectHeader().valueEquals("Idempotency-Replayed", "true")
            .expectBody().jsonPath("$.code").isEqualTo("STALE_REVISION")
    }

    @Test
    fun `paused allocation recovers with the same key and then replays exactly`() {
        val tenant = randomIdentifier()
        val principal = "principal-paused"
        val campaignId = createActiveCampaign(tenant)
        val pauseKey = randomIdentifier()
        operatorPost(
            tenant,
            "/operator/api/v1/campaigns/$campaignId/pause",
            pauseKey,
            mapOf("expectedRevision" to 1),
        ).exchange().expectStatus().isOk
            .expectBody().jsonPath("$.revision").isEqualTo(2)

        val allocationKey = randomIdentifier()
        customerPost(
            tenant,
            principal,
            "/api/v1/campaigns/$campaignId/claims",
            allocationKey,
            mapOf("userRef" to principal),
        ).exchange().expectStatus().isEqualTo(409)
            .expectHeader().valueEquals("Idempotency-Replayed", "false")
            .expectHeader().valueEquals("Retry-After", "1")
            .expectBody().jsonPath("$.code").isEqualTo("CAMPAIGN_PAUSED")

        operatorPost(
            tenant,
            "/operator/api/v1/campaigns/$campaignId/activate",
            randomIdentifier(),
            mapOf("expectedRevision" to 2),
        ).exchange().expectStatus().isOk
            .expectBody().jsonPath("$.revision").isEqualTo(3)

        operatorPost(
            tenant,
            "/operator/api/v1/campaigns/$campaignId/pause",
            pauseKey,
            mapOf("expectedRevision" to 1),
        ).exchange().expectStatus().isOk
            .expectHeader().valueEquals("Idempotency-Replayed", "true")
            .expectBody()
            .jsonPath("$.state").isEqualTo("PAUSED")
            .jsonPath("$.revision").isEqualTo(2)

        val recovered =
            customerPost(
                tenant,
                principal,
                "/api/v1/campaigns/$campaignId/claims",
                allocationKey,
                mapOf("userRef" to principal),
            ).exchange().expectStatus().isCreated
                .expectHeader().valueEquals("Idempotency-Replayed", "false")
                .expectBody(AllocationHttpResponse::class.java)
                .returnResult().responseBody!!

        customerPost(
            tenant,
            principal,
            "/api/v1/campaigns/$campaignId/claims",
            allocationKey,
            mapOf("userRef" to principal),
        ).exchange().expectStatus().isCreated
            .expectHeader().valueEquals("Idempotency-Replayed", "true")
            .expectBody()
            .jsonPath("$.claimId").isEqualTo(recovered.claimId.toString())
            .jsonPath("$.code").isEqualTo(recovered.code!!)
    }

    @Test
    fun `manual reconciliation returns an exactly replayable bounded descriptor`() {
        val tenant = randomIdentifier()
        val key = randomIdentifier()
        val first =
            operatorPost(
                tenant,
                "/operator/api/v1/reconciliation/run",
                key,
                emptyMap<String, Any>(),
            ).exchange().expectStatus().isOk
                .expectHeader().valueEquals("Idempotency-Replayed", "false")
                .expectBody(ReconciliationHttpResponse::class.java)
                .returnResult().responseBody!!
        check(first.processed + first.skipped + first.failed <= 50)

        operatorPost(
            tenant,
            "/operator/api/v1/reconciliation/run",
            key,
            emptyMap<String, Any>(),
        ).exchange().expectStatus().isOk
            .expectHeader().valueEquals("Idempotency-Replayed", "true")
            .expectBody(ReconciliationHttpResponse::class.java)
            .isEqualTo(first)
    }
}
