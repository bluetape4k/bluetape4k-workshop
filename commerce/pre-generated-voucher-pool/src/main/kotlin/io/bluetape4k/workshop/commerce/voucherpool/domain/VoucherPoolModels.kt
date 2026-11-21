package io.bluetape4k.workshop.commerce.voucherpool.domain

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import kotlin.time.Duration

/** voucher campaign의 lifecycle state입니다. */
/** voucher campaign의 lifecycle state입니다. */
enum class CampaignState {
    /**
     * `CampaignState.DRAFT`은 CampaignState 업무 상태의 `DRAFT` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    DRAFT,
    /**
     * `CampaignState.ACTIVE`은 CampaignState 업무 상태의 `ACTIVE` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    ACTIVE,
    /**
     * `CampaignState.PAUSED`은 CampaignState 업무 상태의 `PAUSED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    PAUSED,
    /**
     * `CampaignState.REVOKING`은 CampaignState 업무 상태의 `REVOKING` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를
     * 선택하는 기준으로
     * 사용합니다.
     */
    REVOKING,
    /**
     * `CampaignState.REVOKED`은 CampaignState 업무 상태의 `REVOKED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로
     * 사용합니다.
     */
    REVOKED,
}

/** import되거나 생성된 voucher batch의 lifecycle state입니다. */
/** import되거나 생성된 voucher batch의 lifecycle state입니다. */
enum class BatchState {
    /**
     * `BatchState.STAGING`은 BatchState 업무 상태의 `STAGING` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로
     * 사용합니다.
     */
    STAGING,
    /**
     * `BatchState.ACTIVE`은 BatchState 업무 상태의 `ACTIVE` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로
     * 사용합니다.
     */
    ACTIVE,
    /**
     * `BatchState.PAUSED`은 BatchState 업무 상태의 `PAUSED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로
     * 사용합니다.
     */
    PAUSED,
    /**
     * `BatchState.REVOKING`은 BatchState 업무 상태의 `REVOKING` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    REVOKING,
    /**
     * `BatchState.EXPIRING`은 BatchState 업무 상태의 `EXPIRING` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    EXPIRING,
    /**
     * `BatchState.REVOKED`은 BatchState 업무 상태의 `REVOKED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로
     * 사용합니다.
     */
    REVOKED,
    /**
     * `BatchState.EXPIRED`은 BatchState 업무 상태의 `EXPIRED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로
     * 사용합니다.
     */
    EXPIRED,
    /**
     * `BatchState.FAILED_RETRYABLE`은 BatchState 업무 상태의 `FAILED_RETRYABLE` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를
     * 선택하는 기준으로 사용합니다.
     */
    FAILED_RETRYABLE,
    /**
     * `BatchState.FAILED_TERMINAL`은 BatchState 업무 상태의 `FAILED_TERMINAL` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를
     * 선택하는 기준으로 사용합니다.
     */
    FAILED_TERMINAL,
}

/** 단일 voucher pool entry의 lifecycle state입니다. */
/** 단일 voucher pool entry의 lifecycle state입니다. */
enum class EntryState {
    /**
     * `EntryState.AVAILABLE`은 EntryState 업무 상태의 `AVAILABLE` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    AVAILABLE,
    /**
     * `EntryState.RESERVED`은 EntryState 업무 상태의 `RESERVED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    RESERVED,
    /**
     * `EntryState.ALLOCATED`은 EntryState 업무 상태의 `ALLOCATED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    ALLOCATED,
    /**
     * `EntryState.REDEEMED`은 EntryState 업무 상태의 `REDEEMED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    REDEEMED,
    /**
     * `EntryState.RELEASED`은 EntryState 업무 상태의 `RELEASED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    RELEASED,
    /**
     * `EntryState.REVOKED`은 EntryState 업무 상태의 `REVOKED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로
     * 사용합니다.
     */
    REVOKED,
    /**
     * `EntryState.EXPIRED`은 EntryState 업무 상태의 `EXPIRED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로
     * 사용합니다.
     */
    EXPIRED,
}

/** voucher reservation의 lifecycle state입니다. */
/** voucher reservation의 lifecycle state입니다. */
enum class ReservationState {
    /**
     * `ReservationState.ACTIVE`은 ReservationState 업무 상태의 `ACTIVE` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를
     * 선택하는
     * 기준으로 사용합니다.
     */
    ACTIVE,
    /**
     * `ReservationState.ALLOCATED`은 ReservationState 업무 상태의 `ALLOCATED` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를
     * 선택하는 기준으로 사용합니다.
     */
    ALLOCATED,
    /**
     * `ReservationState.EXPIRED`은 ReservationState 업무 상태의 `EXPIRED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답
     * 분기를 선택하는
     * 기준으로 사용합니다.
     */
    EXPIRED,
    /**
     * `ReservationState.RELEASED`은 ReservationState 업무 상태의 `RELEASED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답
     * 분기를 선택하는
     * 기준으로 사용합니다.
     */
    RELEASED,
    /**
     * `ReservationState.REVOKED`은 ReservationState 업무 상태의 `REVOKED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답
     * 분기를 선택하는
     * 기준으로 사용합니다.
     */
    REVOKED,
}

/** HTTP path와 worker path가 공유하는 안정적인 public error code입니다. */
/** HTTP path와 worker path가 공유하는 안정적인 public error code입니다. */
enum class VoucherPoolErrorCode {
    /**
     * `VoucherPoolErrorCode.COMMAND_IN_PROGRESS`은 VoucherPoolErrorCode 업무 상태의
     * `COMMAND_IN_PROGRESS` 단계입니다.
     * 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    COMMAND_IN_PROGRESS,
    /**
     * `VoucherPoolErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT`은 VoucherPoolErrorCode 업무 상태의
     * `IDEMPOTENCY_FINGERPRINT_CONFLICT` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    IDEMPOTENCY_FINGERPRINT_CONFLICT,
    /**
     * `VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED`은 VoucherPoolErrorCode 업무 상태의
     * `REPLAY_WINDOW_EXPIRED`
     * 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    REPLAY_WINDOW_EXPIRED,
    /**
     * `VoucherPoolErrorCode.POOL_BUSY`은 VoucherPoolErrorCode 업무 상태의 `POOL_BUSY` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    POOL_BUSY,
    /**
     * `VoucherPoolErrorCode.POOL_EXHAUSTED`은 VoucherPoolErrorCode 업무 상태의 `POOL_EXHAUSTED` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    POOL_EXHAUSTED,
    /**
     * `VoucherPoolErrorCode.USER_LIMIT_REACHED`은 VoucherPoolErrorCode 업무 상태의 `USER_LIMIT_REACHED`
     * 단계입니다.
     * 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    USER_LIMIT_REACHED,
    /**
     * `VoucherPoolErrorCode.STALE_REVISION`은 VoucherPoolErrorCode 업무 상태의 `STALE_REVISION` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    STALE_REVISION,
    /**
     * `VoucherPoolErrorCode.CAMPAIGN_NOT_ACTIVE`은 VoucherPoolErrorCode 업무 상태의
     * `CAMPAIGN_NOT_ACTIVE` 단계입니다.
     * 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_NOT_ACTIVE,
    /**
     * `VoucherPoolErrorCode.CAMPAIGN_PAUSED`은 VoucherPoolErrorCode 업무 상태의 `CAMPAIGN_PAUSED` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_PAUSED,
    /**
     * `VoucherPoolErrorCode.CAMPAIGN_REVOKING`은 VoucherPoolErrorCode 업무 상태의 `CAMPAIGN_REVOKING`
     * 단계입니다. 허용된
     * 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_REVOKING,
    /**
     * `VoucherPoolErrorCode.CAMPAIGN_REVOKED`은 VoucherPoolErrorCode 업무 상태의 `CAMPAIGN_REVOKED`
     * 단계입니다. 허용된
     * 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CAMPAIGN_REVOKED,
    /**
     * `VoucherPoolErrorCode.BATCH_PAUSED`은 VoucherPoolErrorCode 업무 상태의 `BATCH_PAUSED` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    BATCH_PAUSED,
    /**
     * `VoucherPoolErrorCode.BATCH_EXPIRING`은 VoucherPoolErrorCode 업무 상태의 `BATCH_EXPIRING` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    BATCH_EXPIRING,
    /**
     * `VoucherPoolErrorCode.BATCH_REVOKED`은 VoucherPoolErrorCode 업무 상태의 `BATCH_REVOKED` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    BATCH_REVOKED,
    /**
     * `VoucherPoolErrorCode.BATCH_EXPIRED`은 VoucherPoolErrorCode 업무 상태의 `BATCH_EXPIRED` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    BATCH_EXPIRED,
    /**
     * `VoucherPoolErrorCode.BATCH_FAILED_RETRYABLE`은 VoucherPoolErrorCode 업무 상태의
     * `BATCH_FAILED_RETRYABLE`
     * 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    BATCH_FAILED_RETRYABLE,
    /**
     * `VoucherPoolErrorCode.BATCH_FAILED_TERMINAL`은 VoucherPoolErrorCode 업무 상태의
     * `BATCH_FAILED_TERMINAL`
     * 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    BATCH_FAILED_TERMINAL,
    /**
     * `VoucherPoolErrorCode.RESERVATION_EXPIRED`은 VoucherPoolErrorCode 업무 상태의
     * `RESERVATION_EXPIRED` 단계입니다.
     * 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    RESERVATION_EXPIRED,
    /**
     * `VoucherPoolErrorCode.ALLOCATION_EXPIRED`은 VoucherPoolErrorCode 업무 상태의 `ALLOCATION_EXPIRED`
     * 단계입니다.
     * 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    ALLOCATION_EXPIRED,
    /**
     * `VoucherPoolErrorCode.WRONG_OWNER`은 VoucherPoolErrorCode 업무 상태의 `WRONG_OWNER` 단계입니다. 허용된 상태
     * 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    WRONG_OWNER,
    /**
     * `VoucherPoolErrorCode.SCOPE_NOT_FOUND`은 VoucherPoolErrorCode 업무 상태의 `SCOPE_NOT_FOUND` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    SCOPE_NOT_FOUND,
    /**
     * `VoucherPoolErrorCode.RATE_LIMITED`은 VoucherPoolErrorCode 업무 상태의 `RATE_LIMITED` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    RATE_LIMITED,
    /**
     * `VoucherPoolErrorCode.BACKEND_TIMEOUT`은 VoucherPoolErrorCode 업무 상태의 `BACKEND_TIMEOUT` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    BACKEND_TIMEOUT,
    /**
     * `VoucherPoolErrorCode.KEY_MATERIAL_UNAVAILABLE`은 VoucherPoolErrorCode 업무 상태의
     * `KEY_MATERIAL_UNAVAILABLE` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    KEY_MATERIAL_UNAVAILABLE,
    /**
     * `VoucherPoolErrorCode.CIPHERTEXT_INVALID`은 VoucherPoolErrorCode 업무 상태의 `CIPHERTEXT_INVALID`
     * 단계입니다.
     * 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    CIPHERTEXT_INVALID,
    /**
     * `VoucherPoolErrorCode.ALREADY_REVEALED`은 VoucherPoolErrorCode 업무 상태의 `ALREADY_REVEALED`
     * 단계입니다. 허용된
     * 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    ALREADY_REVEALED,
}

/**
 * caller가 제공한 Unicode value를 보존하는 검증된 voucher code입니다.
 *
 * code는 256 Unicode code point로 제한되며 ISO control character나 malformed surrogate pair를 포함할 수 없습니다.
 * accidental diagnostics에서 노출되지 않도록 raw value는 [toString]에서 redact됩니다.
 */
class CanonicalVoucherCode private constructor(
    /**
     * `rawValue`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val rawValue: String,
) {
    /** bean property를 노출하지 않고 raw value에 대해 trusted module code를 실행합니다. */
    /** bean property를 노출하지 않고 raw value에 대해 trusted module code를 실행합니다. */
    internal fun <T> withRawValue(block: (String) -> T): T = block(rawValue)

    /**
     * equals은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param other 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun equals(other: Any?): Boolean =
        this === other || (other is CanonicalVoucherCode && rawValue == other.rawValue)

    /**
     * hashCode은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun hashCode(): Int = rawValue.hashCode()

    /**
     * toString은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun toString(): String = "CanonicalVoucherCode([REDACTED])"

    companion object: KLogging() {
        /**
         * `MAX_CODE_POINTS`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        private const val MAX_CODE_POINTS = 256

        /** semantic input boundary를 강제한 뒤 voucher code를 생성합니다. */
        /** semantic input boundary를 강제한 뒤 voucher code를 생성합니다. */
        operator fun invoke(raw: String): CanonicalVoucherCode {
            val validated = raw.requireNotBlank("voucherCode")
            validated
                .codePointCount(0, validated.length)
                .requireInRange(1, MAX_CODE_POINTS, "voucherCode.codePointCount")

            require(validated.none(Char::isISOControl)) {
                "voucherCode must not contain ISO control characters."
            }
            require(validated.hasOnlyWellFormedSurrogates()) {
                "voucherCode must contain well-formed Unicode."
            }
            return CanonicalVoucherCode(validated)
        }

        /** semantic input boundary를 강제한 뒤 voucher code를 생성합니다. */
        /** semantic input boundary를 강제한 뒤 voucher code를 생성합니다. */
        fun of(raw: String): CanonicalVoucherCode =
            invoke(raw)
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
    /**
     * `perUserLimit`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val perUserLimit: Int,
    /**
     * `reservationTtl`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val reservationTtl: Duration,
    /**
     * `allocationTtl`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val allocationTtl: Duration,
    /**
     * `replacementAllowance`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val replacementAllowance: Int,
): Serializable {
    @Suppress("unused")
    /**
     * readResolve은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun readResolve(): Any =
        invoke(
            perUserLimit = perUserLimit,
            reservationTtl = reservationTtl,
            allocationTtl = allocationTtl,
            replacementAllowance = replacementAllowance,
        )

    companion object: KLogging() {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
        /**
         * `MAX_REPLACEMENT_ALLOWANCE`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val MAX_REPLACEMENT_ALLOWANCE = 1

        /** 모든 lifetime과 TTL boundary를 검증한 뒤 policy를 생성합니다. */
        /** 모든 lifetime과 TTL boundary를 검증한 뒤 policy를 생성합니다. */
        operator fun invoke(
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

        /** 모든 lifetime과 TTL boundary를 검증한 뒤 policy를 생성합니다. */
        /** 모든 lifetime과 TTL boundary를 검증한 뒤 policy를 생성합니다. */
        fun of(
            perUserLimit: Int,
            reservationTtl: Duration,
            allocationTtl: Duration,
            replacementAllowance: Int,
        ): VoucherPoolPolicy =
            invoke(perUserLimit, reservationTtl, allocationTtl, replacementAllowance)
    }
}

/**
 * hasOnlyWellFormedSurrogates은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
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
            else                     -> index += 1
        }
    }
    return wellFormed
}
