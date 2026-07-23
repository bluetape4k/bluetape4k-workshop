package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.jackson3.jsonMapper
import io.bluetape4k.support.requireEquals
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignEvent
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import java.time.Instant
import java.util.UUID

private const val CAMPAIGN_STREAM_TYPE = "campaign"

internal class CampaignProjectionEventDecoder {
    private val mapper = jsonMapper { }

    fun decode(event: EventEnvelope): CampaignEvent? {
        if (event.stream.type != CAMPAIGN_STREAM_TYPE) return null
        event.schemaVersion.requireEquals(1, "${event.eventType}.schemaVersion")
        return when (event.eventType) {
            "campaign.created" -> mapper.readValue(event.payload.canonicalJson, CampaignCreatedPayload::class.java)
                .toEvent(event)
            "campaign.activated" -> CampaignEvent.CampaignActivated
            "campaign.capacity-changed" ->
                mapper
                    .readValue(event.payload.canonicalJson, CampaignCapacityChangedPayload::class.java)
                    .let { CampaignEvent.CampaignCapacityChanged(it.capacity) }
            "campaign.voucher-capacity-reserved" ->
                mapper
                    .readValue(event.payload.canonicalJson, VoucherCapacityReservedPayload::class.java)
                    .let { CampaignEvent.VoucherCapacityReserved(it.voucherId, it.policyVersion) }
            else -> error("unsupported campaign event type")
        }
    }
}

private data class CampaignCreatedPayload(
    val startsAt: Instant,
    val endsAt: Instant,
    val capacity: Int,
    val perUserLimit: Int,
    val redemptionTtlSeconds: Long,
) {
    fun toEvent(envelope: EventEnvelope): CampaignEvent.CampaignCreated =
        CampaignEvent.CampaignCreated(
            tenantId = envelope.tenantId,
            campaignId = envelope.stream.id,
            startsAt = startsAt,
            endsAt = endsAt,
            capacity = capacity,
            perUserLimit = perUserLimit,
            redemptionTtlSeconds = redemptionTtlSeconds,
        )
}

private data class CampaignCapacityChangedPayload(
    val capacity: Int,
)

private data class VoucherCapacityReservedPayload(
    val voucherId: UUID,
    val policyVersion: Long,
)
