package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.AppendFences
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.CampaignProjectionReadModels
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventLog
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.IdempotencyReceipts
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.OperatorAudits
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionCheckpoints
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionLeases
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionPoisonEvents
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionProcessedEvents
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionReadModels
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamHeads
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.SubjectIdentityMappings

internal val EVENT_SOURCED_HTTP_TABLES =
    arrayOf(
        EventLog,
        SubjectIdentityMappings,
        StreamHeads,
        AppendFences,
        IdempotencyReceipts,
        OperatorAudits,
        ProjectionGenerations,
        ActiveProjectionGenerations,
        ProjectionLeases,
        ProjectionProcessedEvents,
        ProjectionPoisonEvents,
        ProjectionReadModels,
        CampaignProjectionReadModels,
        ProjectionCheckpoints,
    )
