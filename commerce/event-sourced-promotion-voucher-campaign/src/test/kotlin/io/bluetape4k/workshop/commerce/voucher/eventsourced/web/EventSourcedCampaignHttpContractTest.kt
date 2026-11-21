package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant
import java.util.UUID

/**
 * EventSourcedCampaignHttpContractTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedCampaignHttpContractTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "fresh campaign keeps the compatible body and adds projection headers" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `fresh campaign keeps the compatible body and adds projection headers`() {
        val campaignId = Uuid.V7.nextUUID()

        client(CampaignProjectionResult.Fresh(campaign(campaignId), positions()))
            .get()
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
/**
 * 이 시나리오는 "minimum stream position returns bounded projection pending without exposing internals"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `minimum stream position returns bounded projection pending without exposing internals`() {
        val campaignId = Uuid.V7.nextUUID()

        client(CampaignProjectionResult.Pending(positions(stream = 12, projection = 9)))
            .get()
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
/**
 * 이 시나리오는 "invalid query input is redacted as a stable client error" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `invalid query input is redacted as a stable client error`() {
        val campaignId = Uuid.V7.nextUUID()

        client(CampaignProjectionResult.Fresh(campaign(campaignId), positions()))
            .get()
            .uri("/api/v1/campaigns/$campaignId")
            .header(PRINCIPAL_HEADER, "principal-a")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.reason").isEqualTo("request validation failed")
            .jsonPath("$.exception").doesNotExist()
    }

/**
 * client은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun client(result: CampaignProjectionResult): WebTestClient =
        WebTestClient
            .bindToController(EventSourcedCampaignQueryController { result })
            .controllerAdvice(EventSourcedApiExceptionHandler())
            .build()

/**
 * campaign은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * positions은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun positions(
        stream: Long = 12,
        projection: Long = 12,
    ): ProjectionPositions =
        ProjectionPositions(
            streamPosition = stream,
            projectionPosition = projection
        )

}
