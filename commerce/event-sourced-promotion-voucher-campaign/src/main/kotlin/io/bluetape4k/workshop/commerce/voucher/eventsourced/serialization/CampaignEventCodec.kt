package io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization

import io.bluetape4k.jackson3.jsonMapper
import io.bluetape4k.support.requireEquals
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignEvent
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import java.time.Instant
import java.util.UUID

internal const val CAMPAIGN_STREAM_TYPE = "campaign"

internal data class SerializedCampaignEvent(
    val eventType: String,
    val payload: EventPayload,
)

internal class CampaignEventCodec {
    private val mapper = jsonMapper { }

    fun encode(event: CampaignEvent): SerializedCampaignEvent =
        when (event) {
            is CampaignEvent.CampaignCreated ->
                SerializedCampaignEvent(
                    eventType = "campaign.created",
                    payload =
                        EventPayload(
                            mapper.writeValueAsString(
                                CampaignCreatedPayload(
                                    event.startsAt,
                                    event.endsAt,
                                    event.capacity,
                                    event.perUserLimit,
                                    event.redemptionTtlSeconds,
                                ),
                            ),
                        ),
                )

            CampaignEvent.CampaignActivated ->
                SerializedCampaignEvent("campaign.activated", EventPayload("{}"))

            is CampaignEvent.CampaignCapacityChanged ->
                SerializedCampaignEvent(
                    "campaign.capacity-changed",
                    EventPayload(mapper.writeValueAsString(CampaignCapacityChangedPayload(event.capacity))),
                )

            is CampaignEvent.VoucherCapacityReserved ->
                SerializedCampaignEvent(
                    "campaign.voucher-capacity-reserved",
                    EventPayload(
                        mapper.writeValueAsString(
                            VoucherCapacityReservedPayload(event.voucherId, event.policyVersion),
                        ),
                    ),
                )
        }

    fun decode(event: EventEnvelope): CampaignEvent? {
        if (event.stream.type != CAMPAIGN_STREAM_TYPE) return null
        event.schemaVersion.requireEquals(1, "${event.eventType}.schemaVersion")
        return when (event.eventType) {
            "campaign.created" ->
                mapper
                    .readValue(event.payload.canonicalJson, CampaignCreatedPayload::class.java)
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
