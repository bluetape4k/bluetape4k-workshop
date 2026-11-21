package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.support.checkNotNull
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGeneration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRebuildRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.findActive
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.findBuildingGeneration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.findGeneration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.EventSourcedHmacKeyRing
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.HmacPurpose
import java.time.Clock

/**
 * `MIN_IDEMPOTENCY_KEY_LENGTH`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private const val MIN_IDEMPOTENCY_KEY_LENGTH = 8
/**
 * `MAX_IDEMPOTENCY_KEY_LENGTH`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private const val MAX_IDEMPOTENCY_KEY_LENGTH = 200
/**
 * `STALE_TOKEN_REASON`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
 *
 * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
 */
private const val STALE_TOKEN_REASON = "STALE_GENERATION_TOKEN"
/**
 * `START_CONFLICT_REASON`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
 *
 * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
 */
private const val START_CONFLICT_REASON = "REBUILD_ALREADY_RUNNING"
/**
 * `CANCEL_CONFLICT_REASON`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
 *
 * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
 */
private const val CANCEL_CONFLICT_REASON = "REBUILD_NOT_CANCELLABLE"
/**
 * `RESUME_CONFLICT_REASON`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
 *
 * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
 */
private const val RESUME_CONFLICT_REASON = "REBUILD_NOT_RESUMABLE"
/**
 * RebuildRequestIdentity은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenant 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property principal 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class RebuildRequestIdentity(
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
)
/**
 * StartRebuildRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property identity 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property projection 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property targetPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property expectedToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class StartRebuildRequest(
    /**
     * `identity`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val identity: RebuildRequestIdentity,
    /**
     * `projection`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val projection: String,
    /**
     * `targetPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val targetPosition: Long,
    /**
     * `expectedToken`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val expectedToken: Long,
)
/**
 * RebuildGenerationRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property identity 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property key 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property expectedToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class RebuildGenerationRequest(
    /**
     * `identity`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val identity: RebuildRequestIdentity,
    /**
     * `key`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val key: ProjectionKey,
    /**
     * `expectedToken`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val expectedToken: Long,
)

/**
 * RebuildManagementResult은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal sealed interface RebuildManagementResult {
/**
 * Accepted은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property generation 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property replayed 프로모션 바우처 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 */

    data class Accepted(
        /**
         * `generation`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val generation: ProjectionGeneration,
        /**
         * `replayed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val replayed: Boolean,
    ): RebuildManagementResult
/**
 * StaleToken은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property currentToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


    data class StaleToken(val currentToken: Long): RebuildManagementResult
/**
 * Conflict은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property generation 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property reason 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


    data class Conflict(
        /**
         * `generation`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val generation: ProjectionGeneration?,
        /**
         * `reason`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val reason: String,
    ): RebuildManagementResult

    /**
     * NotFound은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object NotFound: RebuildManagementResult
}
/**
 * RebuildAuditMutation은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property expectedToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property before 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property after 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property outcome 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 * @property reason 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


private data class RebuildAuditMutation(
    /**
     * `expectedToken`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val expectedToken: Long,
    /**
     * `before`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val before: ProjectionGenerationState?,
    /**
     * `after`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val after: ProjectionGenerationState?,
    /**
     * `outcome`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val outcome: OperatorAuditOutcome,
    /**
     * `reason`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reason: String? = null,
)

/**
 * fenced rebuild control boundary입니다. 모든 mutation과 bounded operator audit이 함께 commit됩니다.
 * @property transactions 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property rebuilds 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property audits 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property hmacKeyRing 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property clock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal class EventSourcedRebuildManagementService(
    /**
     * `transactions`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val transactions: EventSourcedPermitTransactionRunner,
    /**
     * `rebuilds`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val rebuilds: ProjectionRebuildRepository,
    /**
     * `audits`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val audits: OperatorAuditRepository,
    /**
     * `hmacKeyRing`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val hmacKeyRing: EventSourcedHmacKeyRing,
    /**
     * `clock`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * start은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun start(request: StartRebuildRequest): RebuildManagementResult =
        transactions.inTransaction {
            /**
             * `validRequest`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
             *
             * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
             */
            val validRequest = request.validated()
            /**
             * `identity`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val identity =
                validRequest.identity.toAuditIdentity(
                    hmacKeyRing,
                    "REBUILD_START",
                    validRequest.fingerprintMaterial(),
                )
            audits.find(identity.tenant, identity.requestDigest, OperatorAuditAction.REBUILD_STARTED)
                ?.let { return@inTransaction replay(it) }

            /**
             * `active`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val active = findActive(validRequest.projection).checkNotNull { "active projection does not exist" }
            /**
             * `activeGeneration`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
             *
             * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
             */
            val activeGeneration = findGeneration(
                ProjectionKey(
                    active.projection,
                    active.generation
                )
            ).checkNotNull { "generation does not exist" }

            if (active.revision != validRequest.expectedToken) {
                return@inTransaction rejectStart(
                    validRequest,
                    identity,
                    activeGeneration,
                    STALE_TOKEN_REASON,
                    active.revision,
                )
            }
            findBuildingGeneration(validRequest.projection)?.let { building ->
                return@inTransaction rejectStart(
                    validRequest,
                    identity,
                    building,
                    START_CONFLICT_REASON,
                    building.fencingToken,
                )
            }
            /**
             * `generation`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
             *
             * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
             */
            val generation = rebuilds.start(validRequest.projection, validRequest.targetPosition, clock.instant())

            audits.append(
                audit(
                    identity = identity,
                    action = OperatorAuditAction.REBUILD_STARTED,
                    generation = generation,
                    mutation =
                        RebuildAuditMutation(
                            expectedToken = validRequest.expectedToken,
                            before = ProjectionGenerationState.ACTIVE,
                            after = generation.state,
                            outcome = OperatorAuditOutcome.APPLIED,
                        ),
                ),
            )
            RebuildManagementResult.Accepted(generation, replayed = false)
        }

    /**
     * status은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun status(request: RebuildGenerationRequest): RebuildManagementResult =
        transactions.inTransaction {
            /**
             * `validRequest`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
             *
             * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
             */
            val validRequest = request.validated()
            validRequest.identity.toAuditIdentity(
                hmacKeyRing,
                "REBUILD_STATUS",
                validRequest.fingerprintMaterial(),
            )
            /**
             * `generation`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
             *
             * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
             */
            val generation = findGeneration(validRequest.key)
                ?: return@inTransaction RebuildManagementResult.NotFound

            if (generation.fencingToken != validRequest.expectedToken) {
                RebuildManagementResult.StaleToken(generation.fencingToken)
            } else {
                RebuildManagementResult.Accepted(generation, replayed = false)
            }
        }

    /**
     * cancel은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun cancel(request: RebuildGenerationRequest): RebuildManagementResult =
        mutateGeneration(
            request = request,
            action = OperatorAuditAction.REBUILD_CANCELLED,
            conflictReason = CANCEL_CONFLICT_REASON
        ) { generation ->
            if (generation.state !in CANCELLABLE_STATES) {
                generation
            } else {
                rebuilds
                    .requestCancellation(generation.key, clock.instant())
                    .checkNotNull { "cancellation does not exist" }
            }
        }

    /**
     * resume은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun resume(request: RebuildGenerationRequest): RebuildManagementResult =
        mutateGeneration(
            request,
            OperatorAuditAction.REBUILD_RESUMED,
            RESUME_CONFLICT_REASON
        ) { generation ->
            if (generation.state != ProjectionGenerationState.FAILED || !generation.retryableFailure) {
                generation
            } else {
                rebuilds.resume(generation.key, clock.instant())!!
            }
        }

    /**
     * mutateGeneration은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param conflictReason 업무 처리에 필요한 conflictReason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param mutation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun mutateGeneration(
        request: RebuildGenerationRequest,
        action: OperatorAuditAction,
        conflictReason: String,
        mutation: (ProjectionGeneration) -> ProjectionGeneration,
    ): RebuildManagementResult =
        transactions.inTransaction {
            /**
             * `validRequest`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
             *
             * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
             */
            val validRequest = request.validated()
            /**
             * `identity`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val identity = validRequest.identity.toAuditIdentity(
                hmacKeyRing,
                action.name,
                validRequest.fingerprintMaterial(),
            )
            audits.find(identity.tenant, identity.requestDigest, action)
                ?.let { return@inTransaction replay(it) }

            /**
             * `before`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val before = findGeneration(validRequest.key) ?: return@inTransaction RebuildManagementResult.NotFound
            if (before.fencingToken != validRequest.expectedToken) {
                audits.append(
                    audit(
                        identity,
                        action,
                        before,
                        RebuildAuditMutation(
                            expectedToken = validRequest.expectedToken,
                            before = before.state,
                            after = before.state,
                            outcome = OperatorAuditOutcome.REJECTED,
                            reason = STALE_TOKEN_REASON,
                        ),
                    ),
                )
                return@inTransaction RebuildManagementResult.StaleToken(before.fencingToken)
            }
            /**
             * `after`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val after = mutation(before)
            /**
             * `applied`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val applied = after.fencingToken != before.fencingToken
            audits.append(
                audit(
                    identity,
                    action,
                    after,
                    RebuildAuditMutation(
                        expectedToken = validRequest.expectedToken,
                        before = before.state,
                        after = after.state,
                        outcome = if (applied) OperatorAuditOutcome.APPLIED else OperatorAuditOutcome.REJECTED,
                        reason = if (applied) null else conflictReason,
                    ),
                ),
            )
            if (applied) {
                RebuildManagementResult.Accepted(after, replayed = false)
            } else {
                RebuildManagementResult.Conflict(after, conflictReason)
            }
        }

    /**
     * rejectStart은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param identity 업무 처리에 필요한 identity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param currentToken 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    private fun rejectStart(
        request: StartRebuildRequest,
        identity: OperatorAuditIdentity,
        generation: ProjectionGeneration,
        reason: String,
        currentToken: Long,
    ): RebuildManagementResult {
        audits.append(
            audit(
                identity,
                OperatorAuditAction.REBUILD_STARTED,
                generation,
                RebuildAuditMutation(
                    expectedToken = request.expectedToken,
                    before = generation.state,
                    after = generation.state,
                    outcome = OperatorAuditOutcome.REJECTED,
                    reason = reason,
                ),
            ),
        )
        return if (reason == STALE_TOKEN_REASON) {
            RebuildManagementResult.StaleToken(currentToken)
        } else {
            RebuildManagementResult.Conflict(generation, reason)
        }
    }

    /**
     * replay은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entry 업무 처리에 필요한 entry 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun replay(entry: OperatorAuditEntry): RebuildManagementResult {
        val generation = findGeneration(ProjectionKey(entry.projection, entry.generation))

        return when {
            entry.outcome == OperatorAuditOutcome.APPLIED && generation != null ->
                RebuildManagementResult.Accepted(generation, replayed = true)
            entry.reasonClass == STALE_TOKEN_REASON ->
                RebuildManagementResult.StaleToken(
                    generation?.fencingToken
                        ?: findActive(entry.projection).checkNotNull("active projection").revision,
                )
            else                                    ->
                RebuildManagementResult.Conflict(generation, entry.reasonClass ?: "REBUILD_CONFLICT")
        }
    }

    /**
     * audit은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param identity 업무 처리에 필요한 identity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param mutation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun audit(
        identity: OperatorAuditIdentity,
        action: OperatorAuditAction,
        generation: ProjectionGeneration,
        mutation: RebuildAuditMutation,
    ): OperatorAuditEntry =
        OperatorAuditEntry(
            identity = identity,
            action = action,
            target = OperatorAuditTarget(
                generation.key.projection,
                generation.key.generation,
                mutation.expectedToken,
            ),
            transition = OperatorAuditTransition(
                beforeState = mutation.before,
                afterState = mutation.after,
                checkpointPosition = generation.currentPosition,
                streamPosition = generation.targetPosition,
                reasonClass = mutation.reason,
            ),
            result = OperatorAuditResult(mutation.outcome, clock.instant()),
        )

    private companion object {
        /**
         * `CANCELLABLE_STATES`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
         *
         * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
         */
        val CANCELLABLE_STATES = setOf(
            ProjectionGenerationState.BUILDING,
            ProjectionGenerationState.VALIDATING
        )
    }
}

/**
 * toAuditIdentity은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param hmacKeyRing 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
 * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param fingerprintMaterial 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
internal fun RebuildRequestIdentity.toAuditIdentity(
    hmacKeyRing: EventSourcedHmacKeyRing,
    action: String,
    fingerprintMaterial: String,
): OperatorAuditIdentity {
    val validTenant = tenant.requireNotBlank("tenant")
    val validPrincipal = principal.requireNotBlank("principal")
    idempotencyKey.length.requireInRange(
        MIN_IDEMPOTENCY_KEY_LENGTH,
        MAX_IDEMPOTENCY_KEY_LENGTH,
        "Idempotency-Key.length",
    )
    val validIdempotencyKey = idempotencyKey.requireNotBlank("idempotencyKey")
    val tenantId = TenantId(validTenant)

    return OperatorAuditIdentity(
        actorDigest = ReceiptDigest.of(
            hmacKeyRing.digest(
                purpose = HmacPurpose.OPERATOR_ACTOR,
                tenantId = tenantId,
                domain = "operator-audit",
                value = validPrincipal,
            ).value,
        ),
        tenant = validTenant,
        requestDigest = ReceiptDigest.of(
            hmacKeyRing.digest(
                purpose = HmacPurpose.OPERATOR_REQUEST,
                tenantId = tenantId,
                domain = action.requireNotBlank("operator.action"),
                value = listOf(fingerprintMaterial, validIdempotencyKey).joinToString("\u0000"),
            ).value,
        ),
    )
}

/**
 * validated은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun StartRebuildRequest.validated(): StartRebuildRequest =
    copy(
        projection = projection.requireNotBlank("projection"),
        targetPosition = targetPosition.requireZeroOrPositiveNumber("targetPosition"),
        expectedToken = expectedToken.requirePositiveNumber("expectedToken"),
    )

/**
 * validated은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun RebuildGenerationRequest.validated(): RebuildGenerationRequest =
    copy(expectedToken = expectedToken.requirePositiveNumber("expectedToken"))

/**
 * fingerprintMaterial은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun StartRebuildRequest.fingerprintMaterial(): String =
    listOf(projection, targetPosition, expectedToken).joinToString("\u0000")

/**
 * fingerprintMaterial은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun RebuildGenerationRequest.fingerprintMaterial(): String =
    listOf(key.projection, key.generation, expectedToken).joinToString("\u0000")
