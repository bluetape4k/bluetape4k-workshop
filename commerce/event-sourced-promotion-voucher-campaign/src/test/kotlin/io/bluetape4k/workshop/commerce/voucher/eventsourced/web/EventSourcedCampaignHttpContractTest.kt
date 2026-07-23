package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.idgenerators.uuid.Uuid
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant
import java.util.UUID

internal class EventSourcedCampaignHttpContractTest {

    @Test
    fun `fresh campaign keeps the compatible body and adds projection headers`() {
        val campaignId = Uuid.V7.nextUUID()
        client(CampaignProjectionResult.Fresh(campaign(campaignId), positions())).get()
            .uri("/api/v1/campaigns/$campaignId")
            .header(TENANT_HEADER, "tenant-a")
            .header(PRINCIPAL_HEADER, "principal-a")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals(STREAM_POSITION_HEADER, "12")
            .expectHeader().valueEquals(PROJECTION_POSITION_HEADER, "12")
            .expectHeader().valueEquals(PROJECTION_LAG_HEADER, "0")
            .expectBody()
            .jsonPath("$.campaignId").isEqualTo(campaignId.toString())
            .jsonPath("$.state").isEqualTo("ACTIVE")
            .jsonPath("$.remainingCapacity").isEqualTo(7)
    }

    @Test
    fun `minimum stream position returns bounded projection pending without exposing internals`() {
        val campaignId = Uuid.V7.nextUUID()
        client(CampaignProjectionResult.Pending(positions(stream = 12, projection = 9))).get()
            .uri("/api/v1/campaigns/$campaignId")
            .header(TENANT_HEADER, "tenant-a")
            .header(PRINCIPAL_HEADER, "principal-a")
            .header(MIN_STREAM_POSITION_HEADER, "12")
            .exchange()
            .expectStatus().isAccepted
            .expectHeader().valueEquals("Retry-After", "1")
            .expectHeader().valueEquals(STREAM_POSITION_HEADER, "12")
            .expectHeader().valueEquals(PROJECTION_POSITION_HEADER, "9")
            .expectHeader().valueEquals(PROJECTION_LAG_HEADER, "3")
            .expectBody()
            .jsonPath("$.code").isEqualTo("PROJECTION_PENDING")
            .jsonPath("$.reason").isEqualTo("projection has not reached the requested position")
            .jsonPath("$.currentStreamPosition").isEqualTo(12)
            .jsonPath("$.projectionPosition").isEqualTo(9)
            .jsonPath("$.lag").isEqualTo(3)
            .jsonPath("$.exception").doesNotExist()
    }

    @Test
    fun `invalid query input is redacted as a stable client error`() {
        val campaignId = Uuid.V7.nextUUID()
        client(CampaignProjectionResult.Fresh(campaign(campaignId), positions())).get()
            .uri("/api/v1/campaigns/$campaignId")
            .header(PRINCIPAL_HEADER, "principal-a")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.reason").isEqualTo("request validation failed")
            .jsonPath("$.exception").doesNotExist()
    }

    private fun client(result: CampaignProjectionResult): WebTestClient =
        WebTestClient.bindToController(EventSourcedCampaignQueryController(CampaignProjectionQuery { result }))
            .controllerAdvice(EventSourcedApiExceptionHandler())
            .build()

    private fun campaign(campaignId: UUID): CampaignHttpResponse =
        CampaignHttpResponse(
            campaignId = campaignId,
            state = "ACTIVE",
            revision = 4,
            policyVersion = 2,
            capacity = 10,
            allocatedCount = 3,
            remainingCapacity = 7,
            startsAt = Instant.parse("2026-07-23T00:00:00Z"),
            endsAt = Instant.parse("2026-07-24T00:00:00Z"),
            observedAt = Instant.parse("2026-07-23T01:00:00Z"),
        )

    private fun positions(
        stream: Long = 12,
        projection: Long = 12,
    ): ProjectionPositions = ProjectionPositions(stream, projection)
}
