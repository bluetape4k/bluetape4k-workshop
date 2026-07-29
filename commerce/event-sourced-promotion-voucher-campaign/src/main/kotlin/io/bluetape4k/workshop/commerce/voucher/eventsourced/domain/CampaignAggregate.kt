package io.bluetape4k.workshop.commerce.voucher.eventsourced.domain

import java.time.Instant
import java.util.UUID

internal class DomainTransitionException(message: String) : IllegalArgumentException(message)

internal data class CampaignAggregate(
    val tenantId: TenantId?,
    val campaignId: UUID?,
    val state: CampaignState,
    val startsAt: Instant?,
    val endsAt: Instant?,
    val capacity: Int,
    val allocatedCount: Int,
    val perUserLimit: Int,
    val redemptionTtlSeconds: Long,
    val policyVersion: Long,
    val version: Long,
) {
    val remainingCapacity: Int
        get() = capacity - allocatedCount

    fun canReserveCapacity(): Boolean = state == CampaignState.ACTIVE && allocatedCount < capacity

    internal fun evolve(event: CampaignEvent): CampaignAggregate =
        when (event) {
            is CampaignEvent.CampaignCreated -> {
                if (campaignId != null) fail("campaign is already created")
                copy(
                    tenantId = event.tenantId,
                    campaignId = event.campaignId,
                    startsAt = event.startsAt,
                    endsAt = event.endsAt,
                    capacity = event.capacity,
                    perUserLimit = event.perUserLimit,
                    redemptionTtlSeconds = event.redemptionTtlSeconds,
                    policyVersion = 1,
                    version = version + 1,
                )
            }

            CampaignEvent.CampaignActivated -> {
                requireCreated()
                if (state != CampaignState.DRAFT && state != CampaignState.PAUSED) {
                    fail("campaign cannot activate from $state")
                }
                copy(state = CampaignState.ACTIVE, version = version + 1)
            }

            is CampaignEvent.CampaignCapacityChanged -> {
                requireCreated()
                if (event.capacity < allocatedCount) fail("campaign capacity cannot drop below allocations")
                copy(capacity = event.capacity, policyVersion = policyVersion + 1, version = version + 1)
            }

            is CampaignEvent.VoucherCapacityReserved -> {
                requireCreated()
                if (!canReserveCapacity()) fail("campaign cannot reserve capacity")
                if (event.policyVersion != policyVersion) fail("campaign policy version does not match")
                copy(allocatedCount = allocatedCount + 1, version = version + 1)
            }
        }

    private fun requireCreated() {
        if (campaignId == null) fail("campaign must be created first")
    }

    private fun fail(message: String): Nothing = throw DomainTransitionException(message)

    companion object {
        fun replay(events: List<CampaignEvent>): CampaignAggregate = events.fold(empty()) { aggregate, event ->
            aggregate.evolve(event)
        }

        private fun empty(): CampaignAggregate =
            CampaignAggregate(
                tenantId = null,
                campaignId = null,
                state = CampaignState.DRAFT,
                startsAt = null,
                endsAt = null,
                capacity = 0,
                allocatedCount = 0,
                perUserLimit = 0,
                redemptionTtlSeconds = 0,
                policyVersion = 0,
                version = 0,
            )
    }
}

@ConsistentCopyVisibility
internal data class EventDecision<E : Any> private constructor(val events: List<E>) {
    companion object {
        fun <E : Any> of(events: List<E>): EventDecision<E> = EventDecision(events.toList())

        fun <E : Any> empty(): EventDecision<E> = EventDecision(emptyList())
    }
}

internal object CampaignCommands {
    fun activate(campaign: CampaignAggregate): EventDecision<CampaignEvent> {
        if (campaign.campaignId == null) throw DomainTransitionException("campaign must be created first")
        if (campaign.state != CampaignState.DRAFT && campaign.state != CampaignState.PAUSED) {
            throw DomainTransitionException("campaign cannot activate from ${campaign.state}")
        }
        return EventDecision.of(listOf(CampaignEvent.CampaignActivated))
    }
}
