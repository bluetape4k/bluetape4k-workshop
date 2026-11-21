@file:Suppress(
    "LongMethod", // The allocation transaction keeps the canonical lock and crypto sequence visible in one place.
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
)

package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.domain.ReservationState
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.SafeResponseDescriptor
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.AllocationRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.LockedAllocationChain
import io.bluetape4k.workshop.commerce.voucherpool.persistence.ReservationRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.EntryIdentity
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherCryptoStorage
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherCryptoStorageOutcome
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigest
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import java.time.Instant
import java.util.*
/**
 * AllocateVoucherCommand은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property reservationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property canonicalUser 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property allocationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */


internal data class AllocateVoucherCommand(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `reservationId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val reservationId: UUID,
    /**
     * `canonicalUser`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val canonicalUser: String,
    /**
     * `expectedRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
    /**
     * `allocationId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val allocationId: UUID = Uuid.V7.nextId(),
)
/**
 * RevealVoucherCommand은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property allocationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property canonicalUser 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class RevealVoucherCommand(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `allocationId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val allocationId: UUID,
    /**
     * `canonicalUser`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val canonicalUser: String,
    /**
     * `expectedRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
)
/**
 * ReplaceLostRevealCommand은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property allocationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property canonicalUser 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property reservationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */


internal data class ReplaceLostRevealCommand(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `allocationId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val allocationId: UUID,
    /**
     * `canonicalUser`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val canonicalUser: String,
    /**
     * `expectedRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
    /**
     * `reservationId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val reservationId: UUID = Uuid.V7.nextId(),
)
/**
 * AllocationSnapshot은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property allocationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property reservationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property batchId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property entryId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property sourceOrdinal 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property expiresAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property entitlementRootId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property replacementOrdinal 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class AllocationSnapshot(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `allocationId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val allocationId: UUID,
    /**
     * `reservationId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val reservationId: UUID,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `batchId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val batchId: UUID,
    /**
     * `entryId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val entryId: UUID,
    /**
     * `sourceOrdinal`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val sourceOrdinal: Long,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: EntryState,
    /**
     * `expiresAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expiresAt: Instant,
    /**
     * `entitlementRootId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val entitlementRootId: UUID,
    /**
     * `replacementOrdinal`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val replacementOrdinal: Int,
    /**
     * `policyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
)

/**
 * RevealResult은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class RevealResult internal constructor(
    /**
     * `allocationId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val allocationId: UUID,
    /**
     * `outcome`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val outcome: String,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
) {
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    @Volatile
    private var committedCode: CanonicalVoucherCode? = null
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */

    val code: CanonicalVoucherCode? get() = committedCode

    /**
     * deliverAfterCommit은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    internal fun deliverAfterCommit(code: CanonicalVoucherCode) {
        check(committedCode == null)
        committedCode = code
    }

    /**
     * toString은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun toString(): String =
        "RevealResult(allocationId=$allocationId,outcome=$outcome,revision=$revision,code=[REDACTED])"
}

/**
 * AllocationService은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal interface AllocationService {
    /**
     * `allocate`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = allocate(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun allocate(command: AllocateVoucherCommand): MutationResult<AllocationSnapshot>
    /**
     * `reveal`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = reveal(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun reveal(command: RevealVoucherCommand): MutationResult<RevealResult>
    /**
     * `replaceLostReveal`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = replaceLostReveal(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun replaceLostReveal(command: ReplaceLostRevealCommand): MutationResult<ReservationSnapshot>
}
/**
 * JdbcAllocationService은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property executor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property repository 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property digests 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property cryptoStorage 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class JdbcAllocationService(
    /**
     * `executor`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: VoucherPoolJdbcExecutor,
    /**
     * `repository`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: VoucherPoolRepository,
    idempotency: VoucherPoolIdempotencyRepository,
    /**
     * `digests`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val digests: VoucherDigestService,
    /**
     * `cryptoStorage`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val cryptoStorage: VoucherCryptoStorage,
): AllocationService {

    companion object: KLogging()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val mutations = LifecycleMutationExecutor(executor, idempotency)

    /**
     * `allocate`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = allocate(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun allocate(command: AllocateVoucherCommand): MutationResult<AllocationSnapshot> =
        mutations.execute(
            tenantId = command.tenantId,
            operation = "voucher-allocate",
            rawKey = command.idempotencyKey,
            fingerprint = fingerprint(
                "voucher-allocate",
                "campaignId" to command.campaignId,
                "reservationId" to command.reservationId,
                "canonicalUser" to command.canonicalUser,
                "expectedRevision" to command.expectedRevision,
            ),
            effectId = command.allocationId,
            successStatus = LIFECYCLE_HTTP_OK,
            lane = LifecycleLane.FOREGROUND,
        ) { _, _ ->
            val userDigest = userIdentity(command.tenantId, command.campaignId, command.canonicalUser)
            val chain = repository.lockReservationChain(
                command.tenantId, command.reservationId, userDigest.copyBytes(),
            ) ?: fail(VoucherPoolErrorCode.WRONG_OWNER)
            requireCampaignUsable(chain.campaign.state)
            requireBatchUsable(chain.batch.state)
            if (chain.reservation.revision != command.expectedRevision) fail(VoucherPoolErrorCode.STALE_REVISION)
            if (chain.reservation.state != ReservationState.ACTIVE.name || chain.entry.state != EntryState.RESERVED) {
                fail(VoucherPoolErrorCode.STALE_REVISION)
            }
            requireCurrentPolicy(chain.reservation, chain.campaign.policyVersion)
            if (chain.entry.quarantinedAt != null) fail(VoucherPoolErrorCode.CIPHERTEXT_INVALID)
            val transactionTime = repository.transactionTime()
            if (chain.reservation.expiresAt <= transactionTime) fail(VoucherPoolErrorCode.RESERVATION_EXPIRED)
            if (chain.batch.expiresAt?.let { it <= transactionTime } == true) fail(VoucherPoolErrorCode.BATCH_EXPIRED)
            val identity = EntryIdentity(
                chain.entry.tenantId,
                chain.entry.campaignId,
                chain.entry.batchId,
                chain.entry.entryId,
                chain.entry.sourceOrdinal,
            )
            val code = when (val decrypted = cryptoStorage.decryptRetained(identity, chain.entry.revision)) {
                is VoucherCryptoStorageOutcome.Revealed -> decrypted.code
                is VoucherCryptoStorageOutcome.Quarantined -> failCommitted(VoucherPoolErrorCode.CIPHERTEXT_INVALID)
            }
            val verification = digests.verification(command.tenantId, command.campaignId, command.allocationId, code)
            check(verification.purpose == DigestPurpose.VERIFICATION)
            val entitlementRoot = chain.reservation.entitlementRootId ?: command.allocationId
            val policyExpiry = transactionTime.plusSeconds(chain.campaign.allocationTtlSeconds)
            val allocation = AllocationRecord(
                command.tenantId,
                command.allocationId,
                command.reservationId,
                command.campaignId,
                chain.entry.batchId,
                chain.entry.entryId,
                DigestValue.of(userDigest.copyBytes()),
                entitlementRoot,
                chain.reservation.replacementOrdinal,
                chain.batch.expiresAt?.let { minOf(it, policyExpiry) } ?: policyExpiry,
                chain.reservation.policyVersion,
                0,
            )
            val allocated = repository.allocateReservation(
                chain,
                allocation,
                verification.copyBytes(),
                verification.keyVersion,
            )
            repository.appendLifecycleAudit(
                allocated.allocation.tenantId,
                allocated.allocation.campaignId,
                "ALLOCATION",
                allocated.allocation.allocationId,
                allocated.allocation.revision,
                allocated.allocation.policyVersion,
                "ALLOCATED",
                "CUSTOMER",
            )
            allocated.snapshot()
        }

    /**
     * `reveal`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = reveal(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun reveal(command: RevealVoucherCommand): MutationResult<RevealResult> {
        val result = mutations.execute(
            tenantId = command.tenantId,
            operation = "voucher-reveal",
            rawKey = command.idempotencyKey,
            fingerprint = fingerprint(
                "voucher-reveal",
                "campaignId" to command.campaignId,
                "allocationId" to command.allocationId,
                "canonicalUser" to command.canonicalUser,
                "expectedRevision" to command.expectedRevision,
            ),
            effectId = command.allocationId,
            successStatus = LIFECYCLE_HTTP_OK,
            lane = LifecycleLane.FOREGROUND,
        ) { _, _ ->
            val digest = userIdentity(command.tenantId, command.campaignId, command.canonicalUser)
            val chain = repository.lockAllocationChain(command.tenantId, command.allocationId, digest.copyBytes())
                ?: fail(VoucherPoolErrorCode.WRONG_OWNER)
            requireCampaignUsable(chain.campaign.state)
            requireBatchUsable(chain.batch.state)
            if (chain.entry.state != EntryState.ALLOCATED) fail(VoucherPoolErrorCode.STALE_REVISION)
            if (chain.entry.quarantinedAt != null) fail(VoucherPoolErrorCode.CIPHERTEXT_INVALID)
            if (chain.allocation.expiresAt <= repository.transactionTime()) fail(VoucherPoolErrorCode.ALLOCATION_EXPIRED)
            if (chain.entry.revealedAt != null) {
                return@execute RevealResult(
                    command.allocationId,
                    VoucherPoolErrorCode.ALREADY_REVEALED.name,
                    chain.allocation.revision,
                )
            }
            if (chain.allocation.revision != command.expectedRevision) fail(VoucherPoolErrorCode.STALE_REVISION)
            val outcome = cryptoStorage.decryptAndErase(
                EntryIdentity(
                    chain.entry.tenantId,
                    chain.entry.campaignId,
                    chain.entry.batchId,
                    chain.entry.entryId,
                    chain.entry.sourceOrdinal,
                ),
                chain.entry.revision,
            )
            when (outcome) {
                is VoucherCryptoStorageOutcome.Quarantined -> failCommitted(VoucherPoolErrorCode.CIPHERTEXT_INVALID)
                is VoucherCryptoStorageOutcome.Revealed -> {
                    val advancedAllocation = repository.advanceAllocationRevision(chain.allocation)
                    val revealed = RevealResult(
                        command.allocationId,
                        "VOUCHER_REVEALED",
                        advancedAllocation.revision,
                    )
                    repository.appendLifecycleAudit(
                        advancedAllocation.tenantId,
                        advancedAllocation.campaignId,
                        "ALLOCATION",
                        advancedAllocation.allocationId,
                        revealed.revision,
                        advancedAllocation.policyVersion,
                        "REVEALED",
                        "CUSTOMER",
                    )
                    executor.afterCommit { revealed.deliverAfterCommit(outcome.code) }
                    revealed
                }
            }
        }
        return when (result) {
            is MutationResult.Replay ->
                if (result.descriptor.terminalCode == null) {
                    MutationResult.Replay(
                        SafeResponseDescriptor.success(
                            LIFECYCLE_HTTP_OK,
                            VoucherPoolErrorCode.ALREADY_REVEALED.name,
                            checkNotNull(result.descriptor.effectId),
                            checkNotNull(result.descriptor.revision),
                        ),
                    )
                } else {
                    result
                }
            else                     -> result
        }
    }

    /**
     * `replaceLostReveal`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = replaceLostReveal(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun replaceLostReveal(command: ReplaceLostRevealCommand): MutationResult<ReservationSnapshot> =
        mutations.execute(
            tenantId = command.tenantId,
            operation = "voucher-replace-lost-reveal",
            rawKey = command.idempotencyKey,
            fingerprint = fingerprint(
                "voucher-replace-lost-reveal",
                "campaignId" to command.campaignId,
                "allocationId" to command.allocationId,
                "canonicalUser" to command.canonicalUser,
                "expectedRevision" to command.expectedRevision,
            ),
            effectId = command.reservationId,
            successStatus = LIFECYCLE_HTTP_CREATED,
            lane = LifecycleLane.FOREGROUND,
        ) { _, owner ->
            val digest = userIdentity(command.tenantId, command.campaignId, command.canonicalUser)
            val chain = repository.lockReplacementChain(
                command.tenantId, command.allocationId, digest.copyBytes(),
            ) ?: fail(VoucherPoolErrorCode.WRONG_OWNER)
            val original = chain.original
            if (original.allocation.replacementOrdinal != 0 || original.entry.state != EntryState.ALLOCATED) {
                failSafe(
                    VoucherPoolErrorCode.ALREADY_REVEALED,
                    original.allocation.allocationId,
                    original.allocation.revision,
                )
            }

            if (original.allocation.revision != command.expectedRevision) fail(VoucherPoolErrorCode.STALE_REVISION)
            requireCampaignUsable(original.campaign.state)
            requireBatchUsable(original.batch.state)
            if (original.campaign.replacementAllowance < 1 || original.entry.revealedAt == null) {
                failSafe(
                    VoucherPoolErrorCode.ALREADY_REVEALED,
                    original.allocation.allocationId,
                    original.allocation.revision,
                )
            }
            val candidate = chain.candidate ?: fail(
                if (chain.availableCandidateExists) VoucherPoolErrorCode.POOL_BUSY else VoucherPoolErrorCode.POOL_EXHAUSTED,
            )
            val replacement = repository.replaceLostReveal(
                chain,
                ReservationRecord(
                    command.tenantId,
                    command.reservationId,
                    original.campaign.campaignId,
                    candidate.batchId,
                    candidate.entryId,
                    DigestValue.of(digest.copyBytes()),
                    DigestValue.of(owner.copyScopedKeyDigest()),
                    ReservationState.ACTIVE.name,
                    repository.transactionTime().plusSeconds(original.campaign.reservationTtlSeconds),
                    original.allocation.entitlementRootId,
                    1,
                    original.allocation.policyVersion,
                    0,
                ),
            )
            repository.appendLifecycleAudit(
                original.allocation.tenantId,
                original.allocation.campaignId,
                "ALLOCATION",
                original.allocation.allocationId,
                original.allocation.revision + 1,
                original.allocation.policyVersion,
                "LOST_REVEAL_REVOKED",
                "CUSTOMER",
            )
            repository.appendLifecycleAudit(
                replacement.tenantId,
                replacement.campaignId,
                "RESERVATION",
                replacement.reservationId,
                replacement.revision,
                replacement.policyVersion,
                "REPLACEMENT_RESERVED",
                "CUSTOMER",
            )
            replacement.snapshot()
        }

    /**
     * userIdentity은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param canonicalUser 업무 처리에 필요한 canonicalUser 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun userIdentity(tenantId: String, campaignId: UUID, canonicalUser: String): VoucherDigest =
        digests.userIdentity(
            tenantId = tenantId,
            campaignId = campaignId,
            canonicalUser = canonicalUser,
            keyVersion = repository.userIdentityKeyVersion(tenantId, campaignId)
                ?: fail(VoucherPoolErrorCode.WRONG_OWNER),
        )
}

/**
 * requireCurrentPolicy은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param reservation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param campaignPolicyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 */
private fun requireCurrentPolicy(reservation: ReservationRecord, campaignPolicyVersion: Long) {
    if (reservation.policyVersion != campaignPolicyVersion) {
        fail(VoucherPoolErrorCode.STALE_REVISION)
    }
}

/**
 * snapshot은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun LockedAllocationChain.snapshot(): AllocationSnapshot =
    AllocationSnapshot(
        allocation.tenantId,
        allocation.allocationId,
        allocation.reservationId,
        allocation.campaignId,
        allocation.batchId,
        allocation.entryId,
        entry.sourceOrdinal,
        entry.state,
        allocation.expiresAt,
        allocation.entitlementRootId,
        allocation.replacementOrdinal,
        allocation.policyVersion,
        allocation.revision,
    )
