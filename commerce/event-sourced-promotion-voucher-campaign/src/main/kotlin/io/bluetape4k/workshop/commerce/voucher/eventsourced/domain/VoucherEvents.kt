package io.bluetape4k.workshop.commerce.voucher.eventsourced.domain

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import java.time.Instant
import java.util.UUID

private const val TENANT_ID_MAX_LENGTH = 64

@JvmInline
value class TenantId(val value: String) {
    init {
        value.requireNotBlank("tenantId")
        value.length.requireInRange(1, TENANT_ID_MAX_LENGTH, "tenantId.length")
    }
}

internal enum class CampaignState {
    DRAFT,
    ACTIVE,
    PAUSED,
    ENDED,
}

internal sealed interface CampaignEvent {
    data class CampaignCreated(
        val tenantId: TenantId,
        val campaignId: UUID,
        val startsAt: Instant,
        val endsAt: Instant,
        val capacity: Int,
        val perUserLimit: Int,
        val redemptionTtlSeconds: Long,
    ) : CampaignEvent {
        init {
            require(startsAt.isBefore(endsAt)) { "campaign start must be before end" }
            require(capacity > 0) { "capacity must be positive" }
            require(perUserLimit > 0) { "perUserLimit must be positive" }
            require(redemptionTtlSeconds > 0) { "redemptionTtlSeconds must be positive" }
        }
    }

    data object CampaignActivated : CampaignEvent

    data class CampaignCapacityChanged(val capacity: Int) : CampaignEvent {
        init {
            require(capacity > 0) { "capacity must be positive" }
        }
    }

    data class VoucherCapacityReserved(
        val voucherId: UUID,
        val policyVersion: Long,
    ) : CampaignEvent {
        init {
            require(policyVersion > 0) { "policyVersion must be positive" }
        }
    }
}

internal enum class VoucherState {
    ELIGIBLE,
    ALLOCATED,
    REDEEMED,
    RELEASED,
    EXPIRED,
    REVOKED,
}

internal sealed interface VoucherEvent {
    data class VoucherIssued(
        val tenantId: TenantId,
        val campaignId: UUID,
        val voucherId: UUID,
        val subjectId: UUID,
    ) : VoucherEvent

    data class VoucherAllocated(
        val policyVersion: Long,
        val expiresAt: Instant,
    ) : VoucherEvent {
        init {
            require(policyVersion > 0) { "policyVersion must be positive" }
        }
    }

    data class VoucherRedeemed(val redeemedAt: Instant) : VoucherEvent

    data object VoucherReleased : VoucherEvent

    data object VoucherExpired : VoucherEvent

    data object VoucherRevoked : VoucherEvent
}
