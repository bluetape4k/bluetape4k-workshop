package io.bluetape4k.workshop.commerce.shared.voucher

import io.bluetape4k.javatimes.days
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.support.toUtf8Bytes
import java.time.Instant
import java.util.*

/**
 * normalized-state 예제와 event-sourced voucher 예제가 공유하는 표준 요청이다.
 *
 * 각 모듈은 HTTP adapter만 소유한다. 시나리오와 normalized result vocabulary는
 * 이곳에 두어 한 모듈의 test fixture를 복사하는 방식으로 compatibility를 주장하지 못하게 한다.
 */
class VoucherCampaignBlackBoxRequest private constructor(
    /**
     * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val tenant: String,
    /**
     * `principal`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val principal: String,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `startsAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val startsAt: Instant,
    /**
     * `endsAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val endsAt: Instant,
    /**
     * `capacity`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val capacity: Int,
    /**
     * `perUserLimit`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val perUserLimit: Int,
    /**
     * `redemptionTtlSeconds`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val redemptionTtlSeconds: Long,
) {
    companion object {
        /**
         * 검증된 black-box campaign request를 만든다.
         *
         * caller가 제공한 identity, interval, limit 값은 공통 bluetape4k
         * [IllegalArgumentException] validation contract를 그대로 따른다.
         */
        operator fun invoke(
            tenant: String,
            principal: String,
            idempotencyKey: String,
            campaignId: UUID,
            startsAt: Instant,
            endsAt: Instant,
            capacity: Int,
            perUserLimit: Int,
            redemptionTtlSeconds: Long,
        ): VoucherCampaignBlackBoxRequest =
            VoucherCampaignBlackBoxRequest(
                tenant = tenant.requireNotBlank("tenant"),
                principal = principal.requireNotBlank("principal"),
                idempotencyKey = idempotencyKey.requireNotBlank("idempotencyKey"),
                campaignId = campaignId,
                startsAt = startsAt,
                endsAt = endsAt.requireGt(startsAt, "endsAt"),
                capacity = capacity.requirePositiveNumber("capacity"),
                perUserLimit = perUserLimit.requirePositiveNumber("perUserLimit"),
                redemptionTtlSeconds = redemptionTtlSeconds.requirePositiveNumber("redemptionTtlSeconds"),
            )
    }
}

/**
 * 모든 compatibility adapter가 검증하는 backend-neutral campaign result다.
 */
class NormalizedVoucherCampaignResult private constructor(
    /**
     * `status`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: Int,
    /**
     * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: String?,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID?,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: String?,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long?,
    /**
     * `policyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long?,
    /**
     * `capacity`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val capacity: Int?,
    /**
     * `remainingCapacity`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val remainingCapacity: Int?,
    /**
     * `replayed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val replayed: Boolean?,
) {
    companion object {
        /**
         * invoke은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
         * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         * @param revision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param policyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param capacity 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
         * @param remainingCapacity 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
         * @param replayed 업무 처리에 필요한 replayed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        operator fun invoke(
            status: Int,
            code: String?,
            campaignId: UUID?,
            state: String?,
            revision: Long?,
            policyVersion: Long?,
            capacity: Int?,
            remainingCapacity: Int?,
            replayed: Boolean?,
        ): NormalizedVoucherCampaignResult =
            NormalizedVoucherCampaignResult(
                status = status.requireInRange(100, 599, "status"),
                code = code?.requireNotBlank("code"),
                campaignId = campaignId,
                state = state?.requireNotBlank("state"),
                revision = revision?.requireZeroOrPositiveNumber("revision"),
                policyVersion = policyVersion?.requireZeroOrPositiveNumber("policyVersion"),
                capacity = capacity?.requireZeroOrPositiveNumber("capacity"),
                remainingCapacity = remainingCapacity?.requireZeroOrPositiveNumber("remainingCapacity"),
                replayed = replayed,
            )
    }
}

/**
 * 각 모듈의 HTTP adapter를 통해 실행하는 공유 request/response scenario다.
 */
class VoucherCampaignBlackBoxScenario private constructor(
    /**
     * `name`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val name: String,
    /**
     * `first`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val first: VoucherCampaignBlackBoxRequest,
    /**
     * `replay`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val replay: VoucherCampaignBlackBoxRequest? = null,
    /**
     * `expectedFirst`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val expectedFirst: NormalizedVoucherCampaignResult,
    /**
     * `expectedReplay`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val expectedReplay: NormalizedVoucherCampaignResult? = null,
) {
    companion object {
        /**
         * invoke은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param first 업무 처리에 필요한 first 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param replay 업무 처리에 필요한 replay 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param expectedFirst 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param expectedReplay 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         */
        operator fun invoke(
            name: String,
            first: VoucherCampaignBlackBoxRequest,
            replay: VoucherCampaignBlackBoxRequest? = null,
            expectedFirst: NormalizedVoucherCampaignResult,
            expectedReplay: NormalizedVoucherCampaignResult? = null,
        ): VoucherCampaignBlackBoxScenario =
            VoucherCampaignBlackBoxScenario(
                name = name.requireNotBlank("name"),
                first = first,
                replay = replay,
                expectedFirst = expectedFirst,
                expectedReplay = expectedReplay,
            )
    }
}

/**
 * 두 HTTP adapter가 공유하는 표준 campaign activation request다.
 */
class VoucherCampaignActivationRequest private constructor(
    /**
     * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val tenant: String,
    /**
     * `principal`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val principal: String,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `expectedRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
) {
    companion object {
        /**
         * invoke은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
         * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
         * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
         * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
         * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         */
        operator fun invoke(
            tenant: String,
            principal: String,
            idempotencyKey: String,
            campaignId: UUID,
            expectedRevision: Long,
        ): VoucherCampaignActivationRequest =
            VoucherCampaignActivationRequest(
                tenant = tenant.requireNotBlank("tenant"),
                principal = principal.requireNotBlank("principal"),
                idempotencyKey = idempotencyKey.requireNotBlank("idempotencyKey"),
                campaignId = campaignId,
                expectedRevision = expectedRevision.requireZeroOrPositiveNumber("expectedRevision"),
            )
    }
}

/**
 * 공유 create-then-activate flow다. replay는 두 번째 activation event를 append하면 안 된다.
 */
class VoucherCampaignActivationScenario private constructor(
    /**
     * `name`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val name: String,
    /**
     * `create`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val create: VoucherCampaignBlackBoxRequest,
    /**
     * `expectedCreate`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expectedCreate: NormalizedVoucherCampaignResult,
    /**
     * `first`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val first: VoucherCampaignActivationRequest,
    /**
     * `replay`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val replay: VoucherCampaignActivationRequest,
    /**
     * `expectedFirst`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val expectedFirst: NormalizedVoucherCampaignResult,
    /**
     * `expectedReplay`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val expectedReplay: NormalizedVoucherCampaignResult,
) {
    companion object {
        /**
         * invoke은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param create 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         * @param expectedCreate 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param first 업무 처리에 필요한 first 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param replay 업무 처리에 필요한 replay 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param expectedFirst 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param expectedReplay 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         */
        operator fun invoke(
            name: String,
            create: VoucherCampaignBlackBoxRequest,
            expectedCreate: NormalizedVoucherCampaignResult,
            first: VoucherCampaignActivationRequest,
            replay: VoucherCampaignActivationRequest,
            expectedFirst: NormalizedVoucherCampaignResult,
            expectedReplay: NormalizedVoucherCampaignResult,
        ): VoucherCampaignActivationScenario =
            VoucherCampaignActivationScenario(
                name = name.requireNotBlank("name"),
                create = create,
                expectedCreate = expectedCreate,
                first = first,
                replay = replay,
                expectedFirst = expectedFirst,
                expectedReplay = expectedReplay,
            )
    }
}

/**
 * 두 HTTP adapter가 공유하는 표준 voucher allocation request다.
 */
class VoucherAllocationBlackBoxRequest private constructor(
    /**
     * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val tenant: String,
    /**
     * `principal`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val principal: String,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `userRef`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val userRef: String,
) {
    companion object {
        /**
         * invoke은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
         * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
         * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
         * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
         * @param userRef 업무 처리에 필요한 userRef 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        operator fun invoke(
            tenant: String,
            principal: String,
            idempotencyKey: String,
            campaignId: UUID,
            userRef: String,
        ): VoucherAllocationBlackBoxRequest {
            val validPrincipal = principal.requireNotBlank("principal")
            val validUserRef = userRef.requireEquals(validPrincipal, "userRef")

            return VoucherAllocationBlackBoxRequest(
                tenant = tenant.requireNotBlank("tenant"),
                principal = validPrincipal,
                idempotencyKey = idempotencyKey.requireNotBlank("idempotencyKey"),
                campaignId = campaignId,
                userRef = validUserRef,
            )
        }
    }
}

/**
 * backend-neutral voucher allocation result다.
 */
class NormalizedVoucherAllocationResult private constructor(
    /**
     * `status`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: Int,
    /**
     * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: String?,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: String?,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long?,
    /**
     * `policyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long?,
    /**
     * `hasCode`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val hasCode: Boolean?,
    /**
     * `replayed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val replayed: Boolean?,
) {
    companion object {
        /**
         * invoke은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         * @param revision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param policyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param hasCode 업무 처리에 필요한 hasCode 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param replayed 업무 처리에 필요한 replayed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        operator fun invoke(
            status: Int,
            code: String?,
            state: String?,
            revision: Long?,
            policyVersion: Long?,
            hasCode: Boolean?,
            replayed: Boolean?,
        ): NormalizedVoucherAllocationResult =
            NormalizedVoucherAllocationResult(
                status = status.requireInRange(100, 599, "status"),
                code = code?.requireNotBlank("code"),
                state = state?.requireNotBlank("state"),
                revision = revision?.requireZeroOrPositiveNumber("revision"),
                policyVersion = policyVersion?.requireZeroOrPositiveNumber("policyVersion"),
                hasCode = hasCode,
                replayed = replayed,
            )
    }
}

/**
 * 각 voucher implementation이 실행하는 공유 allocate-then-replay flow다.
 *
 * 첫 요청은 voucher 하나를 allocate해야 하며, replay는 capacity를 소비하거나
 * 다른 code를 발급하지 않고 같은 normalized outcome을 반환해야 한다.
 */
class VoucherAllocationBlackBoxScenario private constructor(
    /**
     * `name`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val name: String,
    /**
     * `campaign`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val campaign: VoucherCampaignActivationScenario,
    /**
     * `first`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val first: VoucherAllocationBlackBoxRequest,
    /**
     * `replay`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val replay: VoucherAllocationBlackBoxRequest,
    /**
     * `expectedFirst`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val expectedFirst: NormalizedVoucherAllocationResult,
    /**
     * `expectedReplay`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val expectedReplay: NormalizedVoucherAllocationResult,
) {
    companion object {
        /**
         * invoke은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param campaign 업무 처리에 필요한 campaign 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param first 업무 처리에 필요한 first 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param replay 업무 처리에 필요한 replay 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param expectedFirst 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param expectedReplay 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         */
        operator fun invoke(
            name: String,
            campaign: VoucherCampaignActivationScenario,
            first: VoucherAllocationBlackBoxRequest,
            replay: VoucherAllocationBlackBoxRequest,
            expectedFirst: NormalizedVoucherAllocationResult,
            expectedReplay: NormalizedVoucherAllocationResult,
        ): VoucherAllocationBlackBoxScenario =
            VoucherAllocationBlackBoxScenario(
                name = name.requireNotBlank("name"),
                campaign = campaign,
                first = first,
                replay = replay,
                expectedFirst = expectedFirst,
                expectedReplay = expectedReplay,
            )
    }
}

/**
 * 공유 allocation 이후 검증하는 customer-visible voucher transition이다.
 */
enum class VoucherLifecycleAction {
    /**
     * `VoucherLifecycleAction.REDEEM`은 권리 사용을 확정하는 command입니다.
     */
    REDEEM,
    /**
     * `VoucherLifecycleAction.RELEASE`은 예약·점유 자원을 반환하는 명령 또는 후속 조치입니다.
     */
    RELEASE,
}

/**
 * redeem/release compatibility check에 쓰는 backend-neutral result다.
 */
class NormalizedVoucherLifecycleResult private constructor(
    /**
     * `status`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: Int,
    /**
     * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: String?,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: String?,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long?,
    /**
     * `policyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long?,
    /**
     * `replayed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val replayed: Boolean?,
) {
    companion object {
        /**
         * invoke은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         * @param revision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param policyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param replayed 업무 처리에 필요한 replayed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        operator fun invoke(
            status: Int,
            code: String?,
            state: String?,
            revision: Long?,
            policyVersion: Long?,
            replayed: Boolean?,
        ): NormalizedVoucherLifecycleResult =
            NormalizedVoucherLifecycleResult(
                status = status.requireInRange(100, 599, "status"),
                code = code?.requireNotBlank("code"),
                state = state?.requireNotBlank("state"),
                revision = revision?.requireZeroOrPositiveNumber("revision"),
                policyVersion = policyVersion?.requireZeroOrPositiveNumber("policyVersion"),
                replayed = replayed,
            )
    }
}

/**
 * 공유 allocation-to-transition scenario다. adapter는 자신의 allocation endpoint가
 * 반환한 generated claim identity와 one-time code를 제공한다.
 */
class VoucherLifecycleBlackBoxScenario private constructor(
    /**
     * `name`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val name: String,
    /**
     * `allocation`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val allocation: VoucherAllocationBlackBoxScenario,
    /**
     * `action`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val action: VoucherLifecycleAction,
    /**
     * `transitionIdempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val transitionIdempotencyKey: String,
    /**
     * `redemptionReference`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val redemptionReference: String?,
    /**
     * `expectedFirst`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val expectedFirst: NormalizedVoucherLifecycleResult,
    /**
     * `expectedReplay`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val expectedReplay: NormalizedVoucherLifecycleResult,
) {
    companion object {
        /**
         * invoke은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param allocation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param transitionIdempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
         * @param redemptionReference 업무 처리에 필요한 redemptionReference 값이며 관련 불변조건과 저장·이벤트 계약을 함께
         * 만족해야 합니다.
         * @param expectedFirst 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param expectedReplay 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         */
        operator fun invoke(
            name: String,
            allocation: VoucherAllocationBlackBoxScenario,
            action: VoucherLifecycleAction,
            transitionIdempotencyKey: String,
            redemptionReference: String? = null,
            expectedFirst: NormalizedVoucherLifecycleResult,
            expectedReplay: NormalizedVoucherLifecycleResult,
        ): VoucherLifecycleBlackBoxScenario =
            VoucherLifecycleBlackBoxScenario(
                name = name.requireNotBlank("name"),
                allocation = allocation,
                action = action,
                transitionIdempotencyKey = transitionIdempotencyKey.requireNotBlank("transitionIdempotencyKey"),
                redemptionReference = redemptionReference?.requireNotBlank("redemptionReference"),
                expectedFirst = expectedFirst,
                expectedReplay = expectedReplay,
            )
    }
}

/**
 * optional campaign setup과 거부 요청 전 successful warm-up allocation을 포함하는
 * 공유 allocation rejection scenario다.
 */
class VoucherAllocationFailureBlackBoxScenario private constructor(
    /**
     * `name`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val name: String,
    /**
     * `campaign`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val campaign: VoucherCampaignActivationScenario?,
    /**
     * `activateCampaign`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val activateCampaign: Boolean,
    /**
     * `warmupRequests`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val warmupRequests: List<VoucherAllocationBlackBoxRequest>,
    /**
     * `failureRequest`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val failureRequest: VoucherAllocationBlackBoxRequest,
    /**
     * `expectedFailure`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val expectedFailure: NormalizedVoucherAllocationResult,
) {
    companion object {
        /**
         * invoke은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param campaign 업무 처리에 필요한 campaign 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param activateCampaign 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         * @param warmupRequests 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
         * @param failureRequest 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
         * @param expectedFailure 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         */
        operator fun invoke(
            name: String,
            campaign: VoucherCampaignActivationScenario?,
            activateCampaign: Boolean,
            warmupRequests: List<VoucherAllocationBlackBoxRequest> = emptyList(),
            failureRequest: VoucherAllocationBlackBoxRequest,
            expectedFailure: NormalizedVoucherAllocationResult,
        ): VoucherAllocationFailureBlackBoxScenario =
            VoucherAllocationFailureBlackBoxScenario(
                name = name.requireNotBlank("name"),
                campaign = campaign,
                activateCampaign = activateCampaign,
                warmupRequests = warmupRequests.toList(),
                failureRequest = failureRequest,
                expectedFailure = expectedFailure,
            )
    }
}

/**
 * persistence detail을 노출하지 않고 두 구현이 같은 customer lifecycle failure를
 * 거부한다는 점을 증명하는 input mutation이다.
 */
enum class VoucherLifecycleFailureKind {
    /**
     * `VoucherLifecycleFailureKind.WRONG_CODE`은 입력한 voucher·secret code가 대상 권리와 일치하지 않는 값입니다.
     */
    WRONG_CODE,
    /**
     * `VoucherLifecycleFailureKind.STALE_REVISION`은 호출자가 가진 revision이 저장된 최신 revision보다 오래되어 반영할 수
     * 없는 값입니다.
     */
    STALE_REVISION,
    /**
     * `VoucherLifecycleFailureKind.OTHER_PRINCIPAL`은 현재 요청 주체와 다른 principal이 접근한 값입니다.
     */
    OTHER_PRINCIPAL,
}

/**
 * fresh allocation 이후 실행하는 공유 redeem rejection scenario다.
 */
class VoucherLifecycleFailureBlackBoxScenario private constructor(
    /**
     * `name`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val name: String,
    /**
     * `allocation`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val allocation: VoucherAllocationBlackBoxScenario,
    /**
     * `kind`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val kind: VoucherLifecycleFailureKind,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
    /**
     * `expectedFailure`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val expectedFailure: NormalizedVoucherLifecycleResult,
) {
    companion object {
        /**
         * invoke은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param allocation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
         * @param expectedFailure 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         */
        operator fun invoke(
            name: String,
            allocation: VoucherAllocationBlackBoxScenario,
            kind: VoucherLifecycleFailureKind,
            idempotencyKey: String,
            expectedFailure: NormalizedVoucherLifecycleResult,
        ): VoucherLifecycleFailureBlackBoxScenario =
            VoucherLifecycleFailureBlackBoxScenario(
                name = name.requireNotBlank("name"),
                allocation = allocation,
                kind = kind,
                idempotencyKey = idempotencyKey.requireNotBlank("idempotencyKey"),
                expectedFailure = expectedFailure,
            )
    }
}

/**
 * VoucherCampaignBlackBoxContract은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object VoucherCampaignBlackBoxContract {
    /**
     * 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    private const val TENANT = "voucher-contract"
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private const val PRINCIPAL = "voucher-contract-operator"
    /**
     * `CAPACITY`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private const val CAPACITY = 20

    /** 실행일이 바뀌어도 공유 compatibility scenario가 만료되지 않도록 runtime-relative window를 사용합니다. */
    /** 실행일이 바뀌어도 공유 compatibility scenario가 만료되지 않도록 runtime-relative window를 사용합니다. */
    private val CONTRACT_START: Instant = Instant.now().minus(1.minutes())
    /**
     * 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    private val STARTS_AT: Instant = CONTRACT_START
    /**
     * 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    private val ENDS_AT: Instant = CONTRACT_START.plus(2.days())
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    val createAndReplay: VoucherCampaignBlackBoxScenario =
        VoucherCampaignBlackBoxScenario(
            name = "campaign create and same-key replay",
            first = request("contract-create-001"),
            replay = request("contract-create-001"),
            expectedFirst = created(replayed = false),
            expectedReplay = created(replayed = true),
        )
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */

    val fingerprintConflict: VoucherCampaignBlackBoxScenario =
        VoucherCampaignBlackBoxScenario(
            name = "same key with a different request",
            first = request("contract-conflict-001"),
            replay = request("contract-conflict-001", capacity = CAPACITY + 1),
            expectedFirst = created(replayed = false),
            expectedReplay = NormalizedVoucherCampaignResult(
                status = 409,
                code = "IDEMPOTENCY_FINGERPRINT_CONFLICT",
                campaignId = null,
                state = null,
                revision = null,
                policyVersion = null,
                capacity = null,
                remainingCapacity = null,
                replayed = null,
            ),
        )
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    val scenarios: List<VoucherCampaignBlackBoxScenario> =
        listOf(createAndReplay, fingerprintConflict)
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    val activateAndReplay: VoucherCampaignActivationScenario =
        activationScenario("contract-activate-create-001", "contract-activate-001")
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    val allocateAndReplay: VoucherAllocationBlackBoxScenario =
        allocationScenario(
            createKey = "contract-allocate-create-001",
            activationKey = "contract-allocate-activate-001",
            allocationKey = "contract-allocate-001",
        )
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    val lifecycleScenarios: List<VoucherLifecycleBlackBoxScenario> =
        listOf(
            VoucherLifecycleBlackBoxScenario(
                name = "voucher redeem and same-key replay",
                allocation = allocationScenario(
                    createKey = "contract-redeem-create-001",
                    activationKey = "contract-redeem-activate-001",
                    allocationKey = "contract-redeem-allocate-001",
                ),
                action = VoucherLifecycleAction.REDEEM,
                transitionIdempotencyKey = "contract-redeem-001",
                redemptionReference = "contract-order-001",
                expectedFirst = transitioned("REDEEMED", replayed = false),
                expectedReplay = transitioned("REDEEMED", replayed = true),
            ),
            VoucherLifecycleBlackBoxScenario(
                name = "voucher release and same-key replay",
                allocation = allocationScenario(
                    createKey = "contract-release-create-001",
                    activationKey = "contract-release-activate-001",
                    allocationKey = "contract-release-allocate-001",
                ),
                action = VoucherLifecycleAction.RELEASE,
                transitionIdempotencyKey = "contract-release-001",
                expectedFirst = transitioned("RELEASED", replayed = false),
                expectedReplay = transitioned("RELEASED", replayed = true),
            ),
        )
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    val allocationFailures: List<VoucherAllocationFailureBlackBoxScenario> =
        listOf(
            draftAllocationFailure(),
            missingCampaignAllocationFailure(),
            perUserLimitAllocationFailure(),
        )
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    val lifecycleFailures: List<VoucherLifecycleFailureBlackBoxScenario> =
        listOf(
            lifecycleFailure(
                slug = "wrong-code",
                kind = VoucherLifecycleFailureKind.WRONG_CODE,
                status = 409,
                code = "INVALID_CODE",
            ),
            lifecycleFailure(
                slug = "stale-revision",
                kind = VoucherLifecycleFailureKind.STALE_REVISION,
                status = 412,
                code = "STALE_REVISION",
            ),
            lifecycleFailure(
                slug = "other-principal",
                kind = VoucherLifecycleFailureKind.OTHER_PRINCIPAL,
                status = 404,
                code = "CLAIM_NOT_FOUND",
            ),
        )

    /**
     * allocationScenario은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param createKey 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param activationKey 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param allocationKey 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun allocationScenario(
        createKey: String,
        activationKey: String,
        allocationKey: String,
    ): VoucherAllocationBlackBoxScenario =
        activationScenario(createKey, activationKey).let { campaign ->
            /**
             * `request`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val request = VoucherAllocationBlackBoxRequest(
                tenant = campaign.create.tenant,
                principal = "contract-customer",
                idempotencyKey = allocationKey,
                campaignId = campaign.create.campaignId,
                userRef = "contract-customer",
            )
            VoucherAllocationBlackBoxScenario(
                name = "voucher allocate and same-key replay",
                campaign = campaign,
                first = request,
                replay = request,
                expectedFirst = allocated(replayed = false),
                expectedReplay = allocated(replayed = true),
            )
        }

    /**
     * draftAllocationFailure은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun draftAllocationFailure(): VoucherAllocationFailureBlackBoxScenario {
        val campaign = activationScenario("contract-draft-create-001", "contract-draft-activate-001")
        return VoucherAllocationFailureBlackBoxScenario(
            name = "draft campaign rejects allocation",
            campaign = campaign,
            activateCampaign = false,
            failureRequest = allocationRequest(
                campaign = campaign,
                principal = "contract-draft-customer",
                key = "contract-draft-allocate-001",
            ),
            expectedFailure = allocationFailure(409, "CAMPAIGN_NOT_ACTIVE"),
        )
    }

    /**
     * missingCampaignAllocationFailure은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록
     * 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun missingCampaignAllocationFailure(): VoucherAllocationFailureBlackBoxScenario {
        val campaignId = UUID.nameUUIDFromBytes("contract-missing-campaign".toUtf8Bytes())
        return VoucherAllocationFailureBlackBoxScenario(
            name = "missing campaign rejects allocation without leaking storage details",
            campaign = null,
            activateCampaign = false,
            failureRequest = VoucherAllocationBlackBoxRequest(
                tenant = TENANT,
                principal = "contract-missing-customer",
                idempotencyKey = "contract-missing-allocate-001",
                campaignId = campaignId,
                userRef = "contract-missing-customer",
            ),
            expectedFailure = allocationFailure(404, "CAMPAIGN_NOT_FOUND"),
        )
    }

    /**
     * perUserLimitAllocationFailure은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun perUserLimitAllocationFailure(): VoucherAllocationFailureBlackBoxScenario {
        val campaign = activationScenario(
            "contract-limit-create-001",
            "contract-limit-activate-001",
        )
        val principal = "contract-limit-customer"
        return VoucherAllocationFailureBlackBoxScenario(
            name = "third allocation for one subject exceeds the campaign limit",
            campaign = campaign,
            activateCampaign = true,
            warmupRequests = listOf(
                allocationRequest(campaign, principal, "contract-limit-allocate-001"),
                allocationRequest(campaign, principal, "contract-limit-allocate-002"),
            ),
            failureRequest = allocationRequest(campaign, principal, "contract-limit-allocate-003"),
            expectedFailure = allocationFailure(409, "PER_USER_LIMIT_REACHED"),
        )
    }

    /**
     * lifecycleFailure은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param slug 업무 처리에 필요한 slug 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun lifecycleFailure(
        slug: String,
        kind: VoucherLifecycleFailureKind,
        status: Int,
        code: String,
    ): VoucherLifecycleFailureBlackBoxScenario =
        VoucherLifecycleFailureBlackBoxScenario(
            name = "voucher redeem rejects $slug",
            allocation = allocationScenario(
                createKey = "contract-$slug-create-001",
                activationKey = "contract-$slug-activate-001",
                allocationKey = "contract-$slug-allocate-001",
            ),
            kind = kind,
            idempotencyKey = "contract-$slug-redeem-001",
            expectedFailure = NormalizedVoucherLifecycleResult(
                status = status,
                code = code,
                state = null,
                revision = null,
                policyVersion = null,
                replayed = null,
            ),
        )

    /**
     * allocationRequest은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param campaign 업무 처리에 필요한 campaign 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun allocationRequest(
        campaign: VoucherCampaignActivationScenario,
        principal: String,
        key: String,
    ): VoucherAllocationBlackBoxRequest =
        VoucherAllocationBlackBoxRequest(
            tenant = campaign.create.tenant,
            principal = principal,
            idempotencyKey = key,
            campaignId = campaign.create.campaignId,
            userRef = principal,
        )

    /**
     * activationScenario은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param createKey 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param activationKey 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun activationScenario(
        createKey: String,
        activationKey: String,
    ): VoucherCampaignActivationScenario =
        request(createKey).let { create ->
            /**
             * `activation`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val activation = VoucherCampaignActivationRequest(
                tenant = create.tenant,
                principal = create.principal,
                idempotencyKey = activationKey,
                campaignId = create.campaignId,
                expectedRevision = 0,
            )
            VoucherCampaignActivationScenario(
                name = "campaign activate and same-key replay",
                create = create,
                expectedCreate = created(replayed = false),
                first = activation,
                replay = activation,
                expectedFirst = activated(replayed = false),
                expectedReplay = activated(replayed = true),
            )
        }

    /**
     * request은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param capacity 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    private fun request(
        key: String,
        capacity: Int = CAPACITY,
    ): VoucherCampaignBlackBoxRequest =
        VoucherCampaignBlackBoxRequest(
            tenant = TENANT,
            principal = PRINCIPAL,
            idempotencyKey = key,
            campaignId = UUID.nameUUIDFromBytes(key.toUtf8Bytes()),
            startsAt = STARTS_AT,
            endsAt = ENDS_AT,
            capacity = capacity,
            perUserLimit = 2,
            redemptionTtlSeconds = 3_600,
        )

    /**
     * created은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param replayed 업무 처리에 필요한 replayed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun created(replayed: Boolean): NormalizedVoucherCampaignResult =
        NormalizedVoucherCampaignResult(
            status = 201,
            code = null,
            campaignId = null,
            state = "DRAFT",
            revision = 0,
            policyVersion = 0,
            capacity = CAPACITY,
            remainingCapacity = CAPACITY,
            replayed = replayed,
        )

    /**
     * activated은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param replayed 업무 처리에 필요한 replayed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun activated(replayed: Boolean): NormalizedVoucherCampaignResult =
        NormalizedVoucherCampaignResult(
            status = 200,
            code = null,
            campaignId = null,
            state = "ACTIVE",
            revision = 1,
            policyVersion = 0,
            capacity = CAPACITY,
            remainingCapacity = CAPACITY,
            replayed = replayed,
        )

    /**
     * allocated은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param replayed 업무 처리에 필요한 replayed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun allocated(replayed: Boolean): NormalizedVoucherAllocationResult =
        NormalizedVoucherAllocationResult(
            status = 201,
            code = null,
            state = "ALLOCATED",
            revision = 0,
            policyVersion = 0,
            hasCode = true,
            replayed = replayed,
        )

    /**
     * allocationFailure은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun allocationFailure(
        status: Int,
        code: String,
    ): NormalizedVoucherAllocationResult =
        NormalizedVoucherAllocationResult(
            status = status,
            code = code,
            state = null,
            revision = null,
            policyVersion = null,
            hasCode = null,
            replayed = null,
        )

    /**
     * transitioned은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param replayed 업무 처리에 필요한 replayed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun transitioned(
        state: String,
        replayed: Boolean,
    ): NormalizedVoucherLifecycleResult =
        NormalizedVoucherLifecycleResult(
            status = 200,
            code = null,
            state = state,
            revision = 1,
            policyVersion = 0,
            replayed = replayed,
        )

}
