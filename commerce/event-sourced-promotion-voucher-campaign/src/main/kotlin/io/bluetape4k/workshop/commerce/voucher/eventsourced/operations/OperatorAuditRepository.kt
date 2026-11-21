package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.OperatorAudits
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.io.Serializable
import java.time.Instant

/**
 * `OperatorAuditAction`은 업무 분기용 제한된 값 집합입니다.
 * 현재 상태와 aggregate revision을 검증한 뒤 허용된 전이만 실행하는 명령·신호 계약입니다.
 */
internal enum class OperatorAuditAction {
    /**
     * `OperatorAuditAction.REBUILD_STARTED`은 `rebuild started` 업무 상황을 나타냅니다. 현재 상태와 aggregate
     * revision을
     * 검증한 뒤 허용된 전이만 실행합니다.
     */
    REBUILD_STARTED,
    /**
     * `OperatorAuditAction.REBUILD_CANCELLED`은 `rebuild cancelled` 업무 상황을 나타냅니다. 현재 상태와 aggregate
     * revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    REBUILD_CANCELLED,
    /**
     * `OperatorAuditAction.REBUILD_RESUMED`은 `rebuild resumed` 업무 상황을 나타냅니다. 현재 상태와 aggregate
     * revision을
     * 검증한 뒤 허용된 전이만 실행합니다.
     */
    REBUILD_RESUMED,
    /**
     * `OperatorAuditAction.REBUILD_ACTIVATED`은 `rebuild activated` 업무 상황을 나타냅니다. 현재 상태와 aggregate
     * revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    REBUILD_ACTIVATED,
    /**
     * `OperatorAuditAction.POISON_RETRIED`은 `poison retried` 업무 상황을 나타냅니다. 현재 상태와 aggregate
     * revision을 검증한
     * 뒤 허용된 전이만 실행합니다.
     */
    POISON_RETRIED,
    /**
     * `OperatorAuditAction.RECONCILIATION_RUN`은 `reconciliation run` 업무 상황을 나타냅니다. 현재 상태와 aggregate
     * revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    RECONCILIATION_RUN,
}

/**
 * `OperatorAuditOutcome`은 처리 결과입니다.
 * 외부 결과를 성공·재시도·보상·수동 대사로 분기할 때 사용하는 업무 계약입니다.
 */
internal enum class OperatorAuditOutcome {
    /**
     * `OperatorAuditOutcome.APPLIED`은 마이그레이션·명령·outbox 반영이 이번 호출에서 실제로 완료된 값입니다.
     */
    APPLIED,
    /**
     * `OperatorAuditOutcome.REJECTED`은 정책 또는 입력 검증을 통과하지 못해 요청을 수용하지 않은 값입니다.
     */
    REJECTED,
}

/** immutable operator mutation evidence입니다. actor와 request identity의 digest만 저장합니다. */
/** immutable operator mutation evidence입니다. actor와 request identity의 digest만 저장합니다. */
@ConsistentCopyVisibility
internal data class OperatorAuditIdentity private constructor(
    /**
     * `actorDigest`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val actorDigest: ReceiptDigest,
    /**
     * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val tenant: String,
    /**
     * `requestDigest`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val requestDigest: ReceiptDigest,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L

        /**
         * invoke은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param actorDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
         * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
         * @param requestDigest 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
         */
        operator fun invoke(
            actorDigest: ReceiptDigest,
            tenant: String,
            requestDigest: ReceiptDigest,
        ): OperatorAuditIdentity =
            OperatorAuditIdentity(
                actorDigest = actorDigest,
                tenant = tenant.requireNotBlank("tenant"),
                requestDigest = requestDigest
            )
    }
}

@ConsistentCopyVisibility
/**
 * OperatorAuditTarget은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal data class OperatorAuditTarget private constructor(
    /**
     * `projection`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val projection: String,
    /**
     * `generation`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generation: Long,
    /**
     * `expectedFencingToken`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val expectedFencingToken: Long,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L

        /**
         * invoke은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         * @param expectedFencingToken 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         */
        operator fun invoke(
            projection: String,
            generation: Long,
            expectedFencingToken: Long,
        ): OperatorAuditTarget {
            return OperatorAuditTarget(
                projection.requireNotBlank("projection"),
                generation.requirePositiveNumber("generation"),
                expectedFencingToken.requirePositiveNumber("expectedFencingToken"),
            )
        }
    }
}

@ConsistentCopyVisibility
/**
 * OperatorAuditTransition은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal data class OperatorAuditTransition private constructor(
    /**
     * `beforeState`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val beforeState: ProjectionGenerationState?,
    /**
     * `afterState`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val afterState: ProjectionGenerationState?,
    /**
     * `checkpointPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val checkpointPosition: Long,
    /**
     * `streamPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val streamPosition: Long,
    /**
     * `reasonClass`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reasonClass: String?,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
        /**
         * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private val REASON_CLASS = Regex("[A-Z][A-Z0-9_]{0,63}")

        /**
         * invoke은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param beforeState 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         * @param afterState 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         * @param checkpointPosition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param streamPosition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param reasonClass 업무 처리에 필요한 reasonClass 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        operator fun invoke(
            beforeState: ProjectionGenerationState?,
            afterState: ProjectionGenerationState?,
            checkpointPosition: Long,
            streamPosition: Long,
            reasonClass: String?,
        ): OperatorAuditTransition {
            val validCheckpoint = checkpointPosition.requireZeroOrPositiveNumber("checkpointPosition")
            val validStreamPosition = streamPosition.requireZeroOrPositiveNumber("streamPosition")
            require(reasonClass == null || REASON_CLASS.matches(reasonClass)) {
                "reasonClass 가 null 이거나 REASON_CLASS 형식에 맞아야 합니다."
            }

            return OperatorAuditTransition(
                beforeState = beforeState,
                afterState = afterState,
                checkpointPosition = validCheckpoint,
                streamPosition = validStreamPosition,
                reasonClass = reasonClass
            )
        }
    }
}
/**
 * OperatorAuditResult은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property outcome 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 * @property occurredAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


internal data class OperatorAuditResult(
    /**
     * `outcome`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val outcome: OperatorAuditOutcome,
    /**
     * `occurredAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val occurredAt: Instant,
)

@ConsistentCopyVisibility
/**
 * OperatorAuditEntry은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal data class OperatorAuditEntry private constructor(
    /**
     * `identity`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val identity: OperatorAuditIdentity,
    /**
     * `action`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val action: OperatorAuditAction,
    /**
     * `target`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val target: OperatorAuditTarget,
    /**
     * `transition`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val transition: OperatorAuditTransition,
    /**
     * `result`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val result: OperatorAuditResult,
): Serializable {
    /**
     * 호출 주체 참조값입니다. 권한·중복 요청·감사 추적에 사용하며 원문 개인정보는 노출하지 않습니다.
     */
    val actorDigest: ReceiptDigest get() = identity.actorDigest
    /**
     * 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    val tenant: String get() = identity.tenant
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val requestDigest: ReceiptDigest get() = identity.requestDigest
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val projection: String get() = target.projection
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val generation: Long get() = target.generation
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val expectedFencingToken: Long get() = target.expectedFencingToken
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val beforeState: ProjectionGenerationState? get() = transition.beforeState
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val afterState: ProjectionGenerationState? get() = transition.afterState
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val checkpointPosition: Long get() = transition.checkpointPosition
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val streamPosition: Long get() = transition.streamPosition
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val reasonClass: String? get() = transition.reasonClass
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val outcome: OperatorAuditOutcome get() = result.outcome
    /**
     * 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val occurredAt: Instant get() = result.occurredAt

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L

        /**
         * invoke은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param identity 업무 처리에 필요한 identity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param target 업무 처리에 필요한 target 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param transition 업무 처리에 필요한 transition 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
         */
        operator fun invoke(
            identity: OperatorAuditIdentity,
            action: OperatorAuditAction,
            target: OperatorAuditTarget,
            transition: OperatorAuditTransition,
            result: OperatorAuditResult,
        ): OperatorAuditEntry =
            OperatorAuditEntry(identity, action, target, transition, result)
    }
}

/**
 * transaction-bound append-only audit boundary입니다. caller가 자신이 설명하는 fenced mutation transaction과 같은
 * transaction에
 * audit evidence를 저장해야 하므로 generic CRUD API를 의도적으로 제공하지 않습니다.
 */
internal class OperatorAuditRepository {

    companion object: KLogging()

    /**
     * find은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param requestDigest 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun find(
        tenant: String,
        requestDigest: ReceiptDigest,
        action: OperatorAuditAction,
    ): OperatorAuditEntry? {
        TransactionManager.current()

        return OperatorAudits
            .selectAll()
            .where { OperatorAudits.tenant eq tenant }
            .andWhere { OperatorAudits.requestDigest eq requestDigest.value }
            .andWhere { OperatorAudits.action eq action }
            .singleOrNull()
            ?.let(::toAuditEntry)
    }

    /**
     * append은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entry 업무 처리에 필요한 entry 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun append(entry: OperatorAuditEntry): OperatorAuditEntry {
        TransactionManager.current()
        OperatorAudits.insert { row ->
            row[actorDigest] = entry.actorDigest.value
            row[tenant] = entry.tenant
            row[requestDigest] = entry.requestDigest.value
            row[action] = entry.action
            row[projection] = entry.projection
            row[generation] = entry.generation
            row[expectedFencingToken] = entry.expectedFencingToken
            row[beforeState] = entry.beforeState
            row[afterState] = entry.afterState
            row[checkpointPosition] = entry.checkpointPosition
            row[streamPosition] = entry.streamPosition
            row[outcome] = entry.outcome
            row[reasonClass] = entry.reasonClass
            row[occurredAt] = entry.occurredAt
        }
        return entry
    }
}

/**
 * toAuditEntry은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param row 업무 처리에 필요한 row 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun toAuditEntry(row: ResultRow): OperatorAuditEntry =
    OperatorAuditEntry(
        identity = OperatorAuditIdentity(
            actorDigest = ReceiptDigest.of(row[OperatorAudits.actorDigest]),
            tenant = row[OperatorAudits.tenant],
            requestDigest = ReceiptDigest.of(row[OperatorAudits.requestDigest]),
        ),
        action = row[OperatorAudits.action],
        target = OperatorAuditTarget(
            projection = row[OperatorAudits.projection],
            generation = row[OperatorAudits.generation],
            expectedFencingToken = row[OperatorAudits.expectedFencingToken],
        ),
        transition = OperatorAuditTransition(
            beforeState = row[OperatorAudits.beforeState],
            afterState = row[OperatorAudits.afterState],
            checkpointPosition = row[OperatorAudits.checkpointPosition],
            streamPosition = row[OperatorAudits.streamPosition],
            reasonClass = row[OperatorAudits.reasonClass],
        ),
        result = OperatorAuditResult(
            outcome = row[OperatorAudits.outcome],
            occurredAt = row[OperatorAudits.occurredAt],
        ),
    )
