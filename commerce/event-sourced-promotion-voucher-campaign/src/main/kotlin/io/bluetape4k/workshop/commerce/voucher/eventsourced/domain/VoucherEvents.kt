package io.bluetape4k.workshop.commerce.voucher.eventsourced.domain

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireLt
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.time.Instant
import java.util.UUID

private const val TENANT_ID_MAX_LENGTH = 64

@JvmInline
value class TenantId private constructor(val value: String) {
    companion object {
        operator fun invoke(value: String): TenantId {
            val validValue = value.requireNotBlank("tenantId")
            val validLength =
                validValue.length.requireInRange(
                    1,
                    TENANT_ID_MAX_LENGTH,
                    "tenantId.length",
                )
            return TenantId(validValue.take(validLength))
        }
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
            startsAt.requireLt(endsAt, "startsAt")
            capacity.requirePositiveNumber("capacity")
            perUserLimit.requirePositiveNumber("perUserLimit")
            redemptionTtlSeconds.requirePositiveNumber("redemptionTtlSeconds")
        }
    }

    data object CampaignActivated : CampaignEvent

    data class CampaignCapacityChanged(val capacity: Int) : CampaignEvent {
        init {
            capacity.requirePositiveNumber("capacity")
        }
    }

    data class VoucherCapacityReserved(
        val voucherId: UUID,
        val policyVersion: Long,
    ) : CampaignEvent {
        init {
            policyVersion.requirePositiveNumber("policyVersion")
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

@ConsistentCopyVisibility
internal data class VoucherCodeKeyVersions private constructor(
    val generation: Int,
    val verification: Int,
) {
    companion object {
        operator fun invoke(
            generation: Int = 1,
            verification: Int = generation,
        ): VoucherCodeKeyVersions =
            VoucherCodeKeyVersions(
                generation = generation.requirePositiveNumber("generationKeyVersion"),
                verification = verification.requirePositiveNumber("verificationKeyVersion"),
            )
    }
}

internal sealed interface VoucherEvent {
    @ConsistentCopyVisibility
    data class VoucherIssued private constructor(
        val tenantId: TenantId,
        val campaignId: UUID,
        val voucherId: UUID,
        val subjectId: UUID,
        val codeKeyVersions: VoucherCodeKeyVersions,
    ) : VoucherEvent {
        companion object {
            operator fun invoke(
                tenantId: TenantId,
                campaignId: UUID,
                voucherId: UUID,
                subjectId: UUID,
                codeKeyVersions: VoucherCodeKeyVersions = VoucherCodeKeyVersions(),
            ): VoucherIssued =
                VoucherIssued(
                    tenantId = tenantId,
                    campaignId = campaignId,
                    voucherId = voucherId,
                    subjectId = subjectId,
                    codeKeyVersions = codeKeyVersions,
                )
        }
    }

    data class VoucherAllocated(
        val policyVersion: Long,
        val expiresAt: Instant,
    ) : VoucherEvent {
        init {
            policyVersion.requirePositiveNumber("policyVersion")
        }
    }

    data class VoucherRedeemed(val redeemedAt: Instant) : VoucherEvent

    data object VoucherReleased : VoucherEvent

    data object VoucherExpired : VoucherEvent

    data object VoucherRevoked : VoucherEvent
}
