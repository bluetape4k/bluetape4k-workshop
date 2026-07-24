package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.CampaignProjectionReadModel
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal class CampaignProjectionQueryServiceTest {

    @Test
    fun `minimum position retries without holding a database transaction and returns fresh state`() {
        val reads = AtomicInteger()
        val query =
            WaitingCampaignProjectionQuery(
                snapshots = CampaignProjectionSnapshotReader { _, _ ->
                    if (reads.getAndIncrement() == 0) snapshot(projectionPosition = 4L)
                    else snapshot(projectionPosition = 5L)
                },
                clock = FIXED_CLOCK,
                waiter = CampaignProjectionWaiter { true },
            )

        val result = query.campaign(request(minimumPosition = 5L))

        result.shouldBeInstanceOf<CampaignProjectionResult.Fresh>()
        reads.get() shouldBeEqualTo 2
    }

    @Test
    fun `minimum position timeout returns retryable pending metadata`() {
        val nanoTimes = ArrayDeque(listOf(0L, 0L, FIVE_SECONDS_NANOS))
        val query =
            WaitingCampaignProjectionQuery(
                snapshots = CampaignProjectionSnapshotReader { _, _ -> snapshot(projectionPosition = 4L) },
                clock = FIXED_CLOCK,
                nanoTime = { nanoTimes.removeFirst() },
                waiter = CampaignProjectionWaiter { true },
            )

        val result = query.campaign(request(minimumPosition = 5L))

        result.shouldBeInstanceOf<CampaignProjectionResult.Pending>()
        result.positions.projectionPosition shouldBeEqualTo 4L
    }

    private fun request(minimumPosition: Long): CampaignProjectionRequest =
        CampaignProjectionRequest(
            tenant = "tenant-a",
            principal = "principal-a",
            campaignId = CAMPAIGN_ID,
            minimumStreamPosition = minimumPosition,
        )

    private fun snapshot(projectionPosition: Long): CampaignProjectionSnapshot =
        CampaignProjectionSnapshot(
            campaign =
                CampaignProjectionReadModel(
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

    private companion object {
        private const val FIVE_SECONDS_NANOS = 5_000_000_000L
        private val CAMPAIGN_ID = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc201")
        private val STARTS_AT = Instant.parse("2026-07-24T00:00:00Z")
        private val ENDS_AT = Instant.parse("2026-07-31T00:00:00Z")
        private val FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-23T15:00:00Z"), ZoneOffset.UTC)
    }
}
