package io.bluetape4k.workshop.commerce.voucherpool.domain

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import kotlin.time.Duration

/** Lifecycle states for a voucher campaign. */
enum class CampaignState {
    DRAFT,
    ACTIVE,
    PAUSED,
    REVOKING,
    REVOKED,
}

/** Lifecycle states for an imported or generated voucher batch. */
enum class BatchState {
    STAGING,
    ACTIVE,
    PAUSED,
    REVOKING,
    EXPIRING,
    REVOKED,
    EXPIRED,
    FAILED_RETRYABLE,
    FAILED_TERMINAL,
}

/** Lifecycle states for a single voucher pool entry. */
enum class EntryState {
    AVAILABLE,
    RESERVED,
    ALLOCATED,
    REDEEMED,
    RELEASED,
    REVOKED,
    EXPIRED,
}

/** Lifecycle states for a voucher reservation. */
enum class ReservationState {
    ACTIVE,
    ALLOCATED,
    EXPIRED,
    RELEASED,
    REVOKED,
}

/** Stable public error codes shared by HTTP and worker paths. */
enum class VoucherPoolErrorCode {
    COMMAND_IN_PROGRESS,
    IDEMPOTENCY_FINGERPRINT_CONFLICT,
    REPLAY_WINDOW_EXPIRED,
    POOL_BUSY,
    POOL_EXHAUSTED,
    USER_LIMIT_REACHED,
    STALE_REVISION,
    CAMPAIGN_NOT_ACTIVE,
    CAMPAIGN_PAUSED,
    CAMPAIGN_REVOKING,
    CAMPAIGN_REVOKED,
    BATCH_PAUSED,
    BATCH_EXPIRING,
    BATCH_REVOKED,
    BATCH_EXPIRED,
    BATCH_FAILED_RETRYABLE,
    BATCH_FAILED_TERMINAL,
    RESERVATION_EXPIRED,
    ALLOCATION_EXPIRED,
    WRONG_OWNER,
    SCOPE_NOT_FOUND,
    RATE_LIMITED,
    BACKEND_TIMEOUT,
    KEY_MATERIAL_UNAVAILABLE,
    CIPHERTEXT_INVALID,
    ALREADY_REVEALED,
}

/**
 * A validated voucher code that preserves the caller-provided Unicode value.
 *
 * Codes are limited to 256 Unicode code points and cannot contain ISO control
 * characters or malformed surrogate pairs. The raw value is redacted from
 * [toString] so accidental diagnostics do not expose it.
 */
class CanonicalVoucherCode private constructor(
    val value: String,
) : Serializable {
    override fun equals(other: Any?): Boolean =
        this === other || (other is CanonicalVoucherCode && value == other.value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "CanonicalVoucherCode([REDACTED])"

    companion object {
        private const val serialVersionUID: Long = 1L
        private const val MAX_CODE_POINTS = 256

        /** Creates a voucher code after enforcing its semantic input boundary. */
        fun of(raw: String): CanonicalVoucherCode {
            val validated = raw.requireNotBlank("voucherCode")
            validated.codePointCount(0, validated.length)
                .requireInRange(1, MAX_CODE_POINTS, "voucherCode.codePointCount")
            require(validated.none(Char::isISOControl)) {
                "voucherCode must not contain ISO control characters."
            }
            require(validated.hasOnlyWellFormedSurrogates()) {
                "voucherCode must contain well-formed Unicode."
            }
            return CanonicalVoucherCode(validated)
        }
    }
}

/**
 * Immutable campaign policy copied into reservation and allocation snapshots.
 *
 * A campaign must allow at least one voucher per user, both TTLs must be
 * positive, and reveal-loss replacement is bounded to one lifetime recovery.
 */
@ConsistentCopyVisibility
data class VoucherPoolPolicy private constructor(
    val perUserLimit: Int,
    val reservationTtl: Duration,
    val allocationTtl: Duration,
    val replacementAllowance: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
        private const val MAX_REPLACEMENT_ALLOWANCE = 1

        /** Creates a policy after validating all lifetime and TTL boundaries. */
        fun of(
            perUserLimit: Int,
            reservationTtl: Duration,
            allocationTtl: Duration,
            replacementAllowance: Int,
        ): VoucherPoolPolicy {
            val validatedLimit = perUserLimit.requirePositiveNumber("perUserLimit")
            require(reservationTtl.isPositive()) { "reservationTtl must be positive." }
            require(allocationTtl.isPositive()) { "allocationTtl must be positive." }
            val validatedAllowance =
                replacementAllowance.requireInRange(
                    start = 0,
                    endInclusive = MAX_REPLACEMENT_ALLOWANCE,
                    parameterName = "replacementAllowance",
                )
            return VoucherPoolPolicy(
                perUserLimit = validatedLimit,
                reservationTtl = reservationTtl,
                allocationTtl = allocationTtl,
                replacementAllowance = validatedAllowance,
            )
        }
    }
}

private fun String.hasOnlyWellFormedSurrogates(): Boolean {
    var index = 0
    var wellFormed = true
    while (index < length && wellFormed) {
        val current = this[index]
        when {
            current.isHighSurrogate() -> {
                wellFormed = index + 1 < length && this[index + 1].isLowSurrogate()
                index += 2
            }

            current.isLowSurrogate() -> wellFormed = false
            else -> index += 1
        }
    }
    return wellFormed
}
