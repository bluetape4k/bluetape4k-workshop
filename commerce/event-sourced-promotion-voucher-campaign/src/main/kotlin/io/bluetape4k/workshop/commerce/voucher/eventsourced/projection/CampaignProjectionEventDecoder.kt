package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignEvent
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.CampaignEventCodec

internal class CampaignProjectionEventDecoder(
    private val events: CampaignEventCodec = CampaignEventCodec(),
) {

    fun decode(event: EventEnvelope): CampaignEvent? = events.decode(event)
}
