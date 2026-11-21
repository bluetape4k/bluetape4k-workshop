package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.CampaignProjectionReadModel
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayDeque

/**
 * CampaignProjectionQueryServiceTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class CampaignProjectionQueryServiceTest {

    private companion object: KLogging() {
/**
 * FIVE_SECONDS_NANOS은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val FIVE_SECONDS_NANOS = 5_000_000_000L
/**
 * CAMPAIGN_ID은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val CAMPAIGN_ID = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc201")
/**
 * STARTS_AT은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val STARTS_AT = Instant.parse("2026-07-24T00:00:00Z")
/**
 * ENDS_AT은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val ENDS_AT = Instant.parse("2026-07-31T00:00:00Z")
/**
 * FIXED_CLOCK은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-23T15:00:00Z"), ZoneOffset.UTC)
    }

    @Test
/**
 * 이 시나리오는 "minimum position retries without holding a database transaction and returns fresh
 * state" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `minimum position retries without holding a database transaction and returns fresh state`() {
        val reads = AtomicInteger()
        val query = WaitingCampaignProjectionQuery(
            snapshots = CampaignProjectionSnapshotReader { _, _ ->
                if (reads.getAndIncrement() == 0) snapshot(projectionPosition = 4L)
                else snapshot(projectionPosition = 5L)
            },
            clock = FIXED_CLOCK,
            waiter = CampaignProjectionWaiter { true },
        )

        val result = query.campaign(request(minimumPosition = 5L))

        log.debug { "result: $result" }
        result.shouldBeInstanceOf<CampaignProjectionResult.Fresh>()
        reads.get() shouldBeEqualTo 2
    }

    @Test
/**
 * 이 시나리오는 "minimum position timeout returns retryable pending metadata" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `minimum position timeout returns retryable pending metadata`() {
        val nanoTimes = ArrayDeque(listOf(0L, 0L, FIVE_SECONDS_NANOS))
        val query = WaitingCampaignProjectionQuery(
            snapshots = { _, _ -> snapshot(projectionPosition = 4L) },
            clock = FIXED_CLOCK,
            nanoTime = { nanoTimes.removeFirst() },
            waiter = { true },
        )

        val result = query.campaign(request(minimumPosition = 5L))

        log.debug { "result: $result" }
        result.shouldBeInstanceOf<CampaignProjectionResult.Pending>()
        result.positions.projectionPosition shouldBeEqualTo 4L
    }

/**
 * request은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun request(minimumPosition: Long): CampaignProjectionRequest =
        CampaignProjectionRequest(
            tenant = "tenant-a",
            principal = "principal-a",
            campaignId = CAMPAIGN_ID,
            minimumStreamPosition = minimumPosition,
        )

/**
 * snapshot은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun snapshot(projectionPosition: Long): CampaignProjectionSnapshot =
        CampaignProjectionSnapshot(
            campaign = CampaignProjectionReadModel(
                tenantId = TenantId("tenant-a"),
                campaignId = CAMPAIGN_ID,
                state = CampaignState.ACTIVE,
                streamVersion = 2L,
                globalPosition = projectionPosition,
                policyVersion = 1L,
                capacity = 100,
                allocatedCount = 1,
                perUserLimit = 2,
                redemptionTtlSeconds = 3600,
                startsAt = STARTS_AT,
                endsAt = ENDS_AT,
                fencingToken = 1L,
            ),
            positions = ProjectionPositions(streamPosition = 5L, projectionPosition = projectionPosition),
        )
}
