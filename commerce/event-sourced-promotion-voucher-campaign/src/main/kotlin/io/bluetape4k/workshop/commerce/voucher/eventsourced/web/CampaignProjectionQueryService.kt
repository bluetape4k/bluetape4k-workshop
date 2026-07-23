package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabaseLane
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedPermitTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventLog
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.CampaignProjectionReadModel
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.findActive
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.time.Duration
import java.util.concurrent.locks.LockSupport

private const val CAMPAIGN_PROJECTION = "voucher-lifecycle"
private const val MAX_CONSISTENCY_WAIT_SECONDS = 5L
private const val CONSISTENCY_POLL_INTERVAL_MILLIS = 25L
private val MAX_CONSISTENCY_WAIT: Duration = Duration.ofSeconds(MAX_CONSISTENCY_WAIT_SECONDS)
private val CONSISTENCY_POLL_INTERVAL: Duration = Duration.ofMillis(CONSISTENCY_POLL_INTERVAL_MILLIS)

internal data class CampaignProjectionSnapshot(
    val campaign: CampaignProjectionReadModel?,
    val positions: ProjectionPositions,
)

internal fun interface CampaignProjectionSnapshotReader {
    fun read(
        tenantId: TenantId,
        campaignId: java.util.UUID,
    ): CampaignProjectionSnapshot
}

internal class ExposedCampaignProjectionSnapshotReader(
    database: Database,
    permits: EventSourcedDatabasePermitGate,
    private val repository: ProjectionRepository,
) : CampaignProjectionSnapshotReader {
    private val transactions =
        EventSourcedPermitTransactionRunner(database, permits, EventSourcedDatabaseLane.FOREGROUND)

    override fun read(
        tenantId: TenantId,
        campaignId: java.util.UUID,
    ): CampaignProjectionSnapshot =
        transactions.inTransaction {
            val active = findActive(CAMPAIGN_PROJECTION)
            val key = active?.let { ProjectionKey(it.projection, it.generation) }
            val projectionPosition = key?.let(repository::checkpoint)?.position ?: 0L
            val streamPosition =
                EventLog
                    .selectAll()
                    .orderBy(EventLog.globalPosition to SortOrder.DESC)
                    .limit(1)
                    .singleOrNull()
                    ?.get(EventLog.globalPosition)
                    ?: 0L
            CampaignProjectionSnapshot(
                campaign = key?.let { repository.campaign(it, tenantId, campaignId) },
                positions = ProjectionPositions(streamPosition, projectionPosition),
            )
        }
}

internal fun interface CampaignProjectionWaiter {
    fun pause(): Boolean
}

internal class WaitingCampaignProjectionQuery(
    private val snapshots: CampaignProjectionSnapshotReader,
    private val clock: Clock = Clock.systemUTC(),
    private val nanoTime: () -> Long = System::nanoTime,
    private val waiter: CampaignProjectionWaiter = PARKING_WAITER,
) : CampaignProjectionQuery {
    override fun campaign(request: CampaignProjectionRequest): CampaignProjectionResult {
        val startedAt = nanoTime()
        var snapshot = snapshots.read(TenantId(request.tenant), request.campaignId)
        while (snapshot.isBefore(request.minimumStreamPosition) && withinDeadline(startedAt) && waiter.pause()) {
            snapshot = snapshots.read(TenantId(request.tenant), request.campaignId)
        }
        return snapshot.toResult(request.minimumStreamPosition)
    }

    private fun withinDeadline(startedAt: Long): Boolean =
        nanoTime() - startedAt < MAX_CONSISTENCY_WAIT.toNanos()

    private fun CampaignProjectionSnapshot.toResult(minimumPosition: Long?): CampaignProjectionResult =
        when {
            isBefore(minimumPosition) -> CampaignProjectionResult.Pending(positions)
            campaign == null -> CampaignProjectionResult.NotFound
            else -> CampaignProjectionResult.Fresh(campaign.toHttp(clock), positions)
        }

    private companion object {
        val PARKING_WAITER =
            CampaignProjectionWaiter {
                LockSupport.parkNanos(CONSISTENCY_POLL_INTERVAL.toNanos())
                !Thread.currentThread().isInterrupted
            }
    }
}

private fun CampaignProjectionSnapshot.isBefore(minimumPosition: Long?): Boolean =
    minimumPosition != null && positions.projectionPosition < minimumPosition

private fun CampaignProjectionReadModel.toHttp(clock: Clock): CampaignHttpResponse =
    CampaignHttpResponse(
        campaignId = campaignId,
        state = state.name,
        revision = streamVersion - 1,
        policyVersion = policyVersion - 1,
        capacity = capacity,
        allocatedCount = allocatedCount,
        remainingCapacity = (capacity - allocatedCount).coerceAtLeast(0),
        startsAt = startsAt,
        endsAt = endsAt,
        observedAt = clock.instant(),
    )
