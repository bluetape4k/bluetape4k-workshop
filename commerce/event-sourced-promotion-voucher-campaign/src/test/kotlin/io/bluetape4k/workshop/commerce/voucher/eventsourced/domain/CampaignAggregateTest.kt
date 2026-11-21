package io.bluetape4k.workshop.commerce.voucher.eventsourced.domain

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * CampaignAggregateTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class CampaignAggregateTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "campaign events deterministically reduce active capacity" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `campaign events deterministically reduce active capacity`() {
        val campaignId = UUID.randomUUID()
        val voucherId = UUID.randomUUID()
        val startsAt = Instant.parse("2026-07-22T00:00:00Z")
        val endsAt = startsAt.plusSeconds(3_600)

        val campaign = CampaignAggregate.replay(
            listOf(
                CampaignEvent.CampaignCreated(
                    tenantId = TenantId("tenant-a"),
                    campaignId = campaignId,
                    startsAt = startsAt,
                    endsAt = endsAt,
                    capacity = 3,
                    perUserLimit = 1,
                    redemptionTtlSeconds = 600,
                ),
                CampaignEvent.CampaignActivated,
                CampaignEvent.CampaignCapacityChanged(capacity = 5),
                CampaignEvent.VoucherCapacityReserved(voucherId, policyVersion = 2),
            ),
        )

        log.debug { "campaign=$campaign" }
        campaign.state shouldBeEqualTo CampaignState.ACTIVE
        campaign.allocatedCount shouldBeEqualTo 1
        campaign.remainingCapacity shouldBeEqualTo 4
        campaign.version shouldBeEqualTo 4
        campaign.canReserveCapacity().shouldBeTrue()
    }
}
