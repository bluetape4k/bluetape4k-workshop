package io.bluetape4k.workshop.commerce.voucher.eventsourced.domain

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

internal class CampaignAggregateTest {

    companion object: KLogging()

    @Test
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
