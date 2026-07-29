package io.bluetape4k.workshop.commerce.voucherpool.domain

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import kotlin.time.Duration

/** voucher campaign의 lifecycle state입니다. */
enum class CampaignState {
    DRAFT,
    ACTIVE,
    PAUSED,
    REVOKING,
    REVOKED,
}

/** import되거나 생성된 voucher batch의 lifecycle state입니다. */
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

/** 단일 voucher pool entry의 lifecycle state입니다. */
enum class EntryState {
    AVAILABLE,
    RESERVED,
    ALLOCATED,
    REDEEMED,
    RELEASED,
    REVOKED,
    EXPIRED,
}

/** voucher reservation의 lifecycle state입니다. */
enum class ReservationState {
    ACTIVE,
    ALLOCATED,
    EXPIRED,
    RELEASED,
    REVOKED,
}

/** HTTP path와 worker path가 공유하는 안정적인 public error code입니다. */
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
 * caller가 제공한 Unicode value를 보존하는 검증된 voucher code입니다.
 *
 * code는 256 Unicode code point로 제한되며 ISO control character나 malformed surrogate pair를 포함할 수 없습니다.
 * accidental diagnostics에서 노출되지 않도록 raw value는 [toString]에서 redact됩니다.
 */
class CanonicalVoucherCode private constructor(
    private val rawValue: String,
) {
    /** bean property를 노출하지 않고 raw value에 대해 trusted module code를 실행합니다. */
    internal fun <T> withRawValue(block: (String) -> T): T = block(rawValue)

    override fun equals(other: Any?): Boolean =
        this === other || (other is CanonicalVoucherCode && rawValue == other.rawValue)

    override fun hashCode(): Int = rawValue.hashCode()

    override fun toString(): String = "CanonicalVoucherCode([REDACTED])"

    companion object {
        private const val MAX_CODE_POINTS = 256

        /** semantic input boundary를 강제한 뒤 voucher code를 생성합니다. */
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
 * reservation과 allocation snapshot에 복사되는 immutable campaign policy입니다.
 *
 * campaign은 user당 최소 하나의 voucher를 허용해야 하고, 두 TTL은 모두 양수여야 하며,
 * reveal-loss replacement는 lifetime recovery 한 번으로 제한됩니다.
 */
@ConsistentCopyVisibility
data class VoucherPoolPolicy private constructor(
    val perUserLimit: Int,
    val reservationTtl: Duration,
    val allocationTtl: Duration,
    val replacementAllowance: Int,
) : Serializable {
    @Suppress("unused")
    private fun readResolve(): Any =
        of(
            perUserLimit = perUserLimit,
            reservationTtl = reservationTtl,
            allocationTtl = allocationTtl,
            replacementAllowance = replacementAllowance,
        )

    companion object {
        private const val serialVersionUID: Long = 1L
        private const val MAX_REPLACEMENT_ALLOWANCE = 1

        /** 모든 lifetime과 TTL boundary를 검증한 뒤 policy를 생성합니다. */
        fun of(
            perUserLimit: Int,
            reservationTtl: Duration,
            allocationTtl: Duration,
            replacementAllowance: Int,
        ): VoucherPoolPolicy {
            val validatedLimit = perUserLimit.requirePositiveNumber("perUserLimit")
            require(reservationTtl.isFinite() && reservationTtl.isPositive()) {
                "reservationTtl must be finite and positive."
            }
            require(allocationTtl.isFinite() && allocationTtl.isPositive()) {
                "allocationTtl must be finite and positive."
            }
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
