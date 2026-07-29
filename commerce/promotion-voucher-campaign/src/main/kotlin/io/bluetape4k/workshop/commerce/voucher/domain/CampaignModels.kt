package io.bluetape4k.workshop.commerce.voucher.domain

import java.time.Instant
import java.util.UUID

internal enum class CampaignState {
    DRAFT,
    ACTIVE,
    PAUSED,
    ENDED,
}

internal data class CampaignSnapshot(
    val tenantId: String,
    val campaignId: UUID,
    val state: CampaignState,
    val startsAt: Instant,
    val endsAt: Instant,
    val capacity: Int,
    val allocatedCount: Int,
    val perUserLimit: Int,
    val redemptionTtlSeconds: Long,
    val policyVersion: Long,
    val revision: Long,
)
