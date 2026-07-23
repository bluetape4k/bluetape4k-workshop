package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignAggregate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.CampaignProjectionReadModels
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

internal data class CampaignProjectionReadModel(
    val tenantId: TenantId,
    val campaignId: UUID,
    val state: CampaignState,
    val streamVersion: Long,
    val globalPosition: Long,
    val policyVersion: Long,
    val capacity: Int,
    val allocatedCount: Int,
    val perUserLimit: Int,
    val redemptionTtlSeconds: Long,
    val startsAt: Instant,
    val endsAt: Instant,
    val fencingToken: Long,
)

/**
 * Semantic campaign state is reachable only through the fenced projection coordinator.
 * This deliberately exposes no generic CRUD or independent transaction entry point.
 */
internal class CampaignProjectionStore(
    private val events: CampaignProjectionEventDecoder = CampaignProjectionEventDecoder(),
) {
    fun apply(
        key: ProjectionKey,
        lease: ProjectionLease,
        envelope: EventEnvelope,
        now: Instant,
    ) {
        val event = events.decode(envelope) ?: return
        val current = findRow(key, envelope.tenantId, envelope.stream.id)
        val aggregate =
            current
                ?.let(::toReadModel)
                ?.toAggregate()
                ?.evolve(event)
                ?: CampaignAggregate.replay(listOf(event))
        if (current == null) insert(key, lease, envelope, aggregate, now)
        else update(key, lease, envelope, aggregate, now)
    }

    fun find(
        key: ProjectionKey,
        tenantId: TenantId,
        campaignId: UUID,
    ): CampaignProjectionReadModel? = findRow(key, tenantId, campaignId)?.let(::toReadModel)

    private fun insert(
        key: ProjectionKey,
        lease: ProjectionLease,
        envelope: EventEnvelope,
        aggregate: CampaignAggregate,
        now: Instant,
    ) {
        CampaignProjectionReadModels.insert { row ->
            row[projection] = key.projection
            row[generation] = key.generation
            row[tenantId] = envelope.tenantId.value
            row[campaignId] = envelope.stream.id
            write(row, envelope, aggregate, lease, now)
        }
    }

    private fun update(
        key: ProjectionKey,
        lease: ProjectionLease,
        envelope: EventEnvelope,
        aggregate: CampaignAggregate,
        now: Instant,
    ) {
        val updated =
            CampaignProjectionReadModels.update(
                where = {
                    campaignKeyPredicate(key, envelope.tenantId, envelope.stream.id) and
                        (CampaignProjectionReadModels.streamVersion less envelope.stream.version) and
                        (CampaignProjectionReadModels.fencingToken lessEq lease.fencingToken)
                },
            ) { row ->
                write(row, envelope, aggregate, lease, now)
            }
        check(updated == 1) { "campaign projection update was rejected" }
    }

    private fun findRow(
        key: ProjectionKey,
        tenantId: TenantId,
        campaignId: UUID,
    ): ResultRow? =
        CampaignProjectionReadModels
            .selectAll()
            .where { campaignKeyPredicate(key, tenantId, campaignId) }
            .singleOrNull()
}

private fun campaignKeyPredicate(
    key: ProjectionKey,
    tenantId: TenantId,
    campaignId: UUID,
) =
    (CampaignProjectionReadModels.projection eq key.projection) and
        (CampaignProjectionReadModels.generation eq key.generation) and
        (CampaignProjectionReadModels.tenantId eq tenantId.value) and
        (CampaignProjectionReadModels.campaignId eq campaignId)

private fun toReadModel(row: ResultRow): CampaignProjectionReadModel =
    CampaignProjectionReadModel(
        tenantId = TenantId(row[CampaignProjectionReadModels.tenantId]),
        campaignId = row[CampaignProjectionReadModels.campaignId],
        state = row[CampaignProjectionReadModels.state],
        streamVersion = row[CampaignProjectionReadModels.streamVersion],
        globalPosition = row[CampaignProjectionReadModels.globalPosition],
        policyVersion = row[CampaignProjectionReadModels.policyVersion],
        capacity = row[CampaignProjectionReadModels.capacity],
        allocatedCount = row[CampaignProjectionReadModels.allocatedCount],
        perUserLimit = row[CampaignProjectionReadModels.perUserLimit],
        redemptionTtlSeconds = row[CampaignProjectionReadModels.redemptionTtlSeconds],
        startsAt = row[CampaignProjectionReadModels.startsAt],
        endsAt = row[CampaignProjectionReadModels.endsAt],
        fencingToken = row[CampaignProjectionReadModels.fencingToken],
    )

private fun CampaignProjectionReadModel.toAggregate(): CampaignAggregate =
    CampaignAggregate(
        tenantId = tenantId,
        campaignId = campaignId,
        state = state,
        startsAt = startsAt,
        endsAt = endsAt,
        capacity = capacity,
        allocatedCount = allocatedCount,
        perUserLimit = perUserLimit,
        redemptionTtlSeconds = redemptionTtlSeconds,
        policyVersion = policyVersion,
        version = streamVersion,
    )

private fun write(
    row: UpdateBuilder<*>,
    envelope: EventEnvelope,
    aggregate: CampaignAggregate,
    lease: ProjectionLease,
    now: Instant,
) {
    row[CampaignProjectionReadModels.state] = aggregate.state
    row[CampaignProjectionReadModels.streamVersion] = envelope.stream.version
    row[CampaignProjectionReadModels.globalPosition] = envelope.globalPosition
    row[CampaignProjectionReadModels.policyVersion] = aggregate.policyVersion
    row[CampaignProjectionReadModels.capacity] = aggregate.capacity
    row[CampaignProjectionReadModels.allocatedCount] = aggregate.allocatedCount
    row[CampaignProjectionReadModels.perUserLimit] = aggregate.perUserLimit
    row[CampaignProjectionReadModels.redemptionTtlSeconds] = aggregate.redemptionTtlSeconds
    row[CampaignProjectionReadModels.startsAt] = checkNotNull(aggregate.startsAt)
    row[CampaignProjectionReadModels.endsAt] = checkNotNull(aggregate.endsAt)
    row[CampaignProjectionReadModels.fencingToken] = lease.fencingToken
    row[CampaignProjectionReadModels.updatedAt] = now
}
