@file:Suppress("MagicNumber", "MaxLineLength", "TooManyFunctions", "SqlResolve")

package io.bluetape4k.workshop.commerce.voucherpool.worker

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolBatchTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolWorkerClaimTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * `WorkerKind`은 분류 코드입니다.
 * aggregate·event·resource의 종류를 구분해 저장 schema와 routing 규칙을 결정합니다.
 */
internal enum class WorkerKind {
    /**
     * `WorkerKind.RESERVATION_EXPIRY`은 `reservation expiry` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event
     * routing
     * 키를 결정합니다.
     */
    RESERVATION_EXPIRY,
    /**
     * `WorkerKind.ALLOCATION_EXPIRY`은 `allocation expiry` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event
     * routing 키를
     * 결정합니다.
     */
    ALLOCATION_EXPIRY,
    /**
     * `WorkerKind.CAMPAIGN_REVOKE`은 `campaign revoke` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event routing
     * 키를 결정합니다.
     */
    CAMPAIGN_REVOKE,
    /**
     * `WorkerKind.BATCH_REVOKE`은 `batch revoke` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event routing 키를
     * 결정합니다.
     */
    BATCH_REVOKE,
    /**
     * `WorkerKind.BATCH_EXPIRY`은 `batch expiry` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event routing 키를
     * 결정합니다.
     */
    BATCH_EXPIRY,
    /**
     * `WorkerKind.RECONCILIATION`은 `reconciliation` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event routing
     * 키를 결정합니다.
     */
    RECONCILIATION,
    /**
     * `WorkerKind.PURGE`은 `purge` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event routing 키를 결정합니다.
     */
    PURGE,
}
/**
 * WorkerPolicy은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property lease 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property runDeadline 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property maxAttempts 같은 업무를 재시도한 횟수 또는 다음 재시도까지의 대기 정책입니다. 최대 시도 횟수와 terminal failure 규칙을 함께
 * 적용합니다.
 * @property maximumBackoff 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 */


internal data class WorkerPolicy(
    /**
     * `lease`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val lease: Duration = 15.seconds(),
    /**
     * `runDeadline`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val runDeadline: Duration = 30.seconds(),
    /**
     * `maxAttempts`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val maxAttempts: Int = 5,
    /**
     * `maximumBackoff`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val maximumBackoff: Duration = 30.seconds(),
) {
    init {
        require(!lease.isNegative && !lease.isZero)
        require(!runDeadline.isNegative && !runDeadline.isZero)
        require(maxAttempts > 0)
        require(!maximumBackoff.isNegative && !maximumBackoff.isZero)
    }
}
/**
 * WorkerClaim은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property kind 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property scopeId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property owner 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property claimUntil 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property cursor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property attempt 같은 업무를 재시도한 횟수 또는 다음 재시도까지의 대기 정책입니다. 최대 시도 횟수와 terminal failure 규칙을 함께 적용합니다.
 * @property nextAttemptAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 * @property checkpoint 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property runDeadline 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


internal data class WorkerClaim(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `kind`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val kind: WorkerKind,
    /**
     * `scopeId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val scopeId: UUID,
    /**
     * `owner`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val owner: String,
    /**
     * `claimUntil`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val claimUntil: Instant,
    /**
     * `cursor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val cursor: Long,
    /**
     * `attempt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val attempt: Int,
    /**
     * `nextAttemptAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val nextAttemptAt: Instant,
    /**
     * `checkpoint`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val checkpoint: Long,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `runDeadline`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val runDeadline: Instant,
)
/**
 * WorkerClaimSnapshot은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property kind 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property scopeId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property owner 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property claimUntil 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property cursor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property attempt 같은 업무를 재시도한 횟수 또는 다음 재시도까지의 대기 정책입니다. 최대 시도 횟수와 terminal failure 규칙을 함께 적용합니다.
 * @property nextAttemptAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 * @property checkpoint 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property poisonReason 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class WorkerClaimSnapshot(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `kind`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val kind: WorkerKind,
    /**
     * `scopeId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val scopeId: UUID,
    /**
     * `owner`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val owner: String?,
    /**
     * `claimUntil`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val claimUntil: Instant?,
    /**
     * `cursor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val cursor: Long,
    /**
     * `attempt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val attempt: Int,
    /**
     * `nextAttemptAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val nextAttemptAt: Instant,
    /**
     * `checkpoint`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val checkpoint: Long,
    /**
     * `poisonReason`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val poisonReason: String?,
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
    val state: WorkerClaimState
        get() = when {
            poisonReason != null -> WorkerClaimState.POISONED
            owner != null -> WorkerClaimState.OWNED
            attempt > 0   -> WorkerClaimState.RETRYABLE
            else          -> WorkerClaimState.IDLE
        }
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    val nextAction: String
        get() = when (state) {
            WorkerClaimState.POISONED -> "OPERATOR_REVIEW_REQUIRED"
            WorkerClaimState.RETRYABLE -> "RETRY_AFTER_BACKOFF"
            WorkerClaimState.OWNED    -> "WAIT_FOR_CHECKPOINT"
            WorkerClaimState.IDLE     -> "CLAIM_AVAILABLE"
        }
}

/**
 * `WorkerClaimState`은 수명주기 상태입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 */
internal enum class WorkerClaimState {
    /**
     * `WorkerClaimState.IDLE`은 `idle` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를 확인합니다.
     */
    IDLE,
    /**
     * `WorkerClaimState.OWNED`은 `owned` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를 확인합니다.
     */
    OWNED,
    /**
     * `WorkerClaimState.RETRYABLE`은 일시 오류로 판단되어 같은 idempotency key와 operation을 재시도할 수 있는 값입니다.
     */
    RETRYABLE,
    /**
     * `WorkerClaimState.POISONED`은 `poisoned` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를 확인합니다.
     */
    POISONED,
}
/**
 * WorkerFailure은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property snapshot 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property backoffSeconds 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 */


internal data class WorkerFailure(
    /**
     * `snapshot`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val snapshot: WorkerClaimSnapshot,
    /**
     * `backoffSeconds`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val backoffSeconds: Long,
)
/**
 * WorkerClaimCandidate은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property kind 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property scopeId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */


internal data class WorkerClaimCandidate(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `kind`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val kind: WorkerKind,
    /**
     * `scopeId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val scopeId: UUID,
)

/**
 * StaleWorkerClaimException은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class StaleWorkerClaimException: IllegalStateException("worker claim owner or revision is stale")

/**
 * VoucherPoolWorkerRepository은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal interface VoucherPoolWorkerRepository {
    /**
     * findRunnable은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    fun findRunnable(limit: Int): List<WorkerClaimCandidate>
    /**
     * claim은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param scopeId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    fun claim(tenantId: String, kind: WorkerKind, scopeId: UUID, owner: String): WorkerClaim?
    /**
     * checkpoint은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param cursor 업무 처리에 필요한 cursor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun checkpoint(claim: WorkerClaim, cursor: Long): WorkerClaim
    /**
     * fail은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun fail(claim: WorkerClaim, reason: String): WorkerFailure
    /**
     * release은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun release(claim: WorkerClaim): WorkerClaimSnapshot
    /**
     * finalize은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun finalize(claim: WorkerClaim): WorkerClaimSnapshot
    /**
     * snapshot은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param scopeId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun snapshot(tenantId: String, kind: WorkerKind, scopeId: UUID): WorkerClaimSnapshot?
}
/**
 * JdbcVoucherPoolWorkerRepository은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property executor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property policy 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class JdbcVoucherPoolWorkerRepository(
    /**
     * `executor`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: VoucherPoolJdbcExecutor,
    /**
     * `policy`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val policy: WorkerPolicy = WorkerPolicy(),
): VoucherPoolWorkerRepository {

    private companion object: KLogging() {
        /**
         * `MAX_OWNER_LENGTH`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        const val MAX_OWNER_LENGTH = 128
        /**
         * `MAX_BACKOFF_SHIFT`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        const val MAX_BACKOFF_SHIFT = 30
        /**
         * `MAX_RUNNABLE_CANDIDATES`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val MAX_RUNNABLE_CANDIDATES = 64
        /**
         * `COMPLETED_NEXT_ATTEMPT`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val COMPLETED_NEXT_ATTEMPT: Instant = Instant.parse("9999-12-31T23:59:59Z")
        /**
         * `BOUNDED_REASON`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val BOUNDED_REASON = Regex("[A-Z0-9_]{1,64}")
    }

    /**
     * findRunnable은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    override fun findRunnable(limit: Int): List<WorkerClaimCandidate> {
        require(limit in 1..MAX_RUNNABLE_CANDIDATES)
        return executor.workerTransaction {
            VoucherPoolWorkerClaimTable
                .selectAll()
                .where {
                    VoucherPoolWorkerClaimTable.poisonReason.isNull() and
                            (VoucherPoolWorkerClaimTable.nextAttemptAt lessEq org.jetbrains.exposed.v1.javatime.CurrentTimestamp) and
                            (VoucherPoolWorkerClaimTable.ownerId.isNull() or
                                    (VoucherPoolWorkerClaimTable.claimUntil lessEq org.jetbrains.exposed.v1.javatime.CurrentTimestamp))
                }
                .orderBy(
                    VoucherPoolWorkerClaimTable.nextAttemptAt to SortOrder.ASC,
                    VoucherPoolWorkerClaimTable.workerType to SortOrder.ASC,
                    VoucherPoolWorkerClaimTable.tenantId to SortOrder.ASC,
                    VoucherPoolWorkerClaimTable.scopeId to SortOrder.ASC,
                )
                .limit(limit)
                .map {
                    WorkerClaimCandidate(
                        tenantId = it[VoucherPoolWorkerClaimTable.tenantId],
                        kind = WorkerKind.valueOf(it[VoucherPoolWorkerClaimTable.workerType]),
                        scopeId = it[VoucherPoolWorkerClaimTable.scopeId],
                    )
                }
        }
    }

    /**
     * claim은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param scopeId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    override fun claim(tenantId: String, kind: WorkerKind, scopeId: UUID, owner: String): WorkerClaim? {
        require(tenantId.isNotBlank())
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH)

        return executor.workerTransaction {
            val connection = currentConnection()
            insertClaimIfMissing(tenantId, kind, scopeId)
            val current = lockSnapshot(tenantId, kind, scopeId) ?: return@workerTransaction null
            val now = transactionTime(connection)
            if (current.poisonReason != null) return@workerTransaction null
            if (current.nextAttemptAt > now && current.nextAttemptAt != COMPLETED_NEXT_ATTEMPT) {
                return@workerTransaction null
            }
            if (current.owner != null && checkNotNull(current.claimUntil) > now) return@workerTransaction null
            val updated = VoucherPoolWorkerClaimTable.update({
                (VoucherPoolWorkerClaimTable.tenantId eq tenantId) and
                        (VoucherPoolWorkerClaimTable.workerType eq kind.name) and
                        (VoucherPoolWorkerClaimTable.scopeId eq scopeId) and
                        (VoucherPoolWorkerClaimTable.revision eq current.revision)
            }) {
                it[VoucherPoolWorkerClaimTable.ownerId] = owner
                it[VoucherPoolWorkerClaimTable.claimUntil] = now.plus(policy.lease)
                it[VoucherPoolWorkerClaimTable.revision] = VoucherPoolWorkerClaimTable.revision + 1
            }
            check(updated == 1)
            checkNotNull(lockSnapshot(tenantId, kind, scopeId)).ownedClaim(now + policy.runDeadline)
        }
    }

    /**
     * checkpoint은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param cursor 업무 처리에 필요한 cursor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun checkpoint(claim: WorkerClaim, cursor: Long): WorkerClaim {
        require(cursor >= 0L)
        return executor.workerTransaction { checkpointInTransaction(currentConnection(), claim, cursor) }
    }

    /**
     * checkpointInTransaction은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분
     * 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param cursor 업무 처리에 필요한 cursor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    internal fun checkpointInTransaction(connection: Connection, claim: WorkerClaim, cursor: Long): WorkerClaim {
        require(cursor >= 0L)
        val current = requireCurrentOwner(connection, claim)
        val now = transactionTime(connection)
        if (claim.runDeadline <= now) throw StaleWorkerClaimException()

        val updated = VoucherPoolWorkerClaimTable.update({
            (VoucherPoolWorkerClaimTable.tenantId eq claim.tenantId) and
                    (VoucherPoolWorkerClaimTable.workerType eq claim.kind.name) and
                    (VoucherPoolWorkerClaimTable.scopeId eq claim.scopeId) and
                    (VoucherPoolWorkerClaimTable.revision eq current.revision) and
                    (VoucherPoolWorkerClaimTable.ownerId eq claim.owner)
        }) {
            it[VoucherPoolWorkerClaimTable.cursor] = cursor
            it[VoucherPoolWorkerClaimTable.checkpoint] = VoucherPoolWorkerClaimTable.checkpoint + 1
            it[VoucherPoolWorkerClaimTable.attempt] = 0
            it[VoucherPoolWorkerClaimTable.nextAttemptAt] = now
            it[VoucherPoolWorkerClaimTable.claimUntil] = now.plus(policy.lease)
            it[VoucherPoolWorkerClaimTable.revision] = VoucherPoolWorkerClaimTable.revision + 1
        }
        check(updated == 1)
        return checkNotNull(
            lockSnapshot(
                claim.tenantId,
                claim.kind,
                claim.scopeId
            )
        ).ownedClaim(claim.runDeadline)
    }

    /**
     * requireCurrentInTransaction은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와
     * 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    internal fun requireCurrentInTransaction(connection: Connection, claim: WorkerClaim) {
        requireCurrentOwner(connection, claim)
    }

    /**
     * fail은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun fail(claim: WorkerClaim, reason: String): WorkerFailure {
        require(reason.matches(BOUNDED_REASON))
        return executor.workerTransaction {
            val connection = currentConnection()
            val current = requireCurrentOwner(connection, claim)
            val now = transactionTime(connection)
            val nextAttempt = current.attempt + 1
            val backoffSeconds = boundedBackoff(nextAttempt)
            val poisonReason = reason.takeIf { nextAttempt >= policy.maxAttempts }

            val updated = VoucherPoolWorkerClaimTable.update({
                (VoucherPoolWorkerClaimTable.tenantId eq claim.tenantId) and
                        (VoucherPoolWorkerClaimTable.workerType eq claim.kind.name) and
                        (VoucherPoolWorkerClaimTable.scopeId eq claim.scopeId) and
                        (VoucherPoolWorkerClaimTable.revision eq current.revision) and
                        (VoucherPoolWorkerClaimTable.ownerId eq claim.owner)
            }) {
                it[VoucherPoolWorkerClaimTable.ownerId] = null
                it[VoucherPoolWorkerClaimTable.claimUntil] = null
                it[VoucherPoolWorkerClaimTable.attempt] = nextAttempt
                it[VoucherPoolWorkerClaimTable.nextAttemptAt] = now.plusSeconds(backoffSeconds)
                it[VoucherPoolWorkerClaimTable.poisonReason] = poisonReason
                it[VoucherPoolWorkerClaimTable.revision] = VoucherPoolWorkerClaimTable.revision + 1
            }
            check(updated == 1)
            WorkerFailure(
                checkNotNull(lockSnapshot(claim.tenantId, claim.kind, claim.scopeId)),
                backoffSeconds,
            )
        }
    }

    /**
     * release은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun release(claim: WorkerClaim): WorkerClaimSnapshot = complete(claim, resetCursor = false)

    /**
     * finalize은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun finalize(claim: WorkerClaim): WorkerClaimSnapshot = complete(claim, resetCursor = true)

    /**
     * snapshot은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param scopeId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    override fun snapshot(tenantId: String, kind: WorkerKind, scopeId: UUID): WorkerClaimSnapshot? =
        executor.workerTransaction { selectSnapshot(tenantId, kind, scopeId, forUpdate = false) }

    /**
     * ensureClaimInTransaction은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분
     * 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param scopeId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    internal fun ensureClaimInTransaction(
        tenantId: String,
        kind: WorkerKind,
        scopeId: UUID,
    ): Boolean = insertClaimIfMissing(tenantId, kind, scopeId)

    /**
     * scheduleReconciliationInTransaction은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency
     * key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param scopeId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    internal fun scheduleReconciliationInTransaction(
        tenantId: String,
        scopeId: UUID,
    ): Boolean {
        return when {
            insertClaimIfMissing(tenantId, WorkerKind.RECONCILIATION, scopeId) -> true
            else -> {
                val current = lockSnapshot(tenantId, WorkerKind.RECONCILIATION, scopeId)
                val completed = current != null &&
                        current.owner == null &&
                        current.cursor == 0L &&
                        current.attempt == 0 &&
                        current.poisonReason == null &&
                        current.checkpoint > 0L
                if (!completed) {
                    false
                } else {
                    VoucherPoolWorkerClaimTable.update({
                        (VoucherPoolWorkerClaimTable.tenantId eq tenantId) and
                                (VoucherPoolWorkerClaimTable.workerType eq WorkerKind.RECONCILIATION.name) and
                                (VoucherPoolWorkerClaimTable.scopeId eq scopeId) and
                                (VoucherPoolWorkerClaimTable.revision eq checkNotNull(current).revision)
                    }) {
                        it[VoucherPoolWorkerClaimTable.checkpoint] = 0
                        it[VoucherPoolWorkerClaimTable.nextAttemptAt] =
                            org.jetbrains.exposed.v1.javatime.CurrentTimestamp
                        it[VoucherPoolWorkerClaimTable.revision] = VoucherPoolWorkerClaimTable.revision + 1
                    } == 1
                }
            }
        }
    }

    /**
     * ensureBatchRevokeClaimsForCampaignInTransaction은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용
     * 전에 검증하여
     * 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    internal fun ensureBatchRevokeClaimsForCampaignInTransaction(
        tenantId: String,
        campaignId: UUID,
        limit: Int,
    ): Int {
        val batchIds = VoucherPoolBatchTable
            .select(VoucherPoolBatchTable.batchId)
            .where {
                (VoucherPoolBatchTable.tenantId eq tenantId) and
                        (VoucherPoolBatchTable.campaignId eq campaignId) and
                        (VoucherPoolBatchTable.state notInList listOf(BatchState.REVOKED, BatchState.EXPIRED))
            }
            .orderBy(VoucherPoolBatchTable.batchId to SortOrder.ASC)
            .map { it[VoucherPoolBatchTable.batchId] }
        var inserted = 0
        for (batchId in batchIds) {
            if (insertClaimIfMissing(tenantId, WorkerKind.BATCH_REVOKE, batchId)) inserted++
            if (inserted == limit) break
        }
        return inserted
    }

    /**
     * hasUnscheduledBatchRevokesInTransaction은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미
     * 처리된 요청은
     * 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    internal fun hasUnscheduledBatchRevokesInTransaction(
        tenantId: String,
        campaignId: UUID,
    ): Boolean {
        val batchIds = VoucherPoolBatchTable
            .select(VoucherPoolBatchTable.batchId)
            .where {
                (VoucherPoolBatchTable.tenantId eq tenantId) and
                        (VoucherPoolBatchTable.campaignId eq campaignId) and
                        (VoucherPoolBatchTable.state notInList listOf(BatchState.REVOKED, BatchState.EXPIRED))
            }
            .map { it[VoucherPoolBatchTable.batchId] }
        return batchIds.any { batchId ->
            VoucherPoolWorkerClaimTable
                .select(VoucherPoolWorkerClaimTable.scopeId)
                .where {
                    (VoucherPoolWorkerClaimTable.tenantId eq tenantId) and
                            (VoucherPoolWorkerClaimTable.workerType eq WorkerKind.BATCH_REVOKE.name) and
                            (VoucherPoolWorkerClaimTable.scopeId eq batchId)
                }
                .singleOrNull() == null
        }
    }

    /**
     * complete은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param resetCursor 업무 처리에 필요한 resetCursor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun complete(claim: WorkerClaim, resetCursor: Boolean): WorkerClaimSnapshot =
        executor.workerTransaction {
            completeInTransaction(currentConnection(), claim, resetCursor)
        }

    /**
     * completeInTransaction은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로
     * 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param resetCursor 업무 처리에 필요한 resetCursor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    internal fun completeInTransaction(
        connection: Connection,
        claim: WorkerClaim,
        resetCursor: Boolean = true,
    ): WorkerClaimSnapshot {
        /**
         * `current`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val current = requireCurrentOwner(connection, claim)
        /**
         * `now`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val now = transactionTime(connection)
        /**
         * `updated`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val updated = VoucherPoolWorkerClaimTable.update({
            (VoucherPoolWorkerClaimTable.tenantId eq claim.tenantId) and
                    (VoucherPoolWorkerClaimTable.workerType eq claim.kind.name) and
                    (VoucherPoolWorkerClaimTable.scopeId eq claim.scopeId) and
                    (VoucherPoolWorkerClaimTable.revision eq current.revision) and
                    (VoucherPoolWorkerClaimTable.ownerId eq claim.owner)
        }) {
            it[VoucherPoolWorkerClaimTable.ownerId] = null
            it[VoucherPoolWorkerClaimTable.claimUntil] = null
            it[VoucherPoolWorkerClaimTable.cursor] = if (resetCursor) 0L else current.cursor
            it[VoucherPoolWorkerClaimTable.nextAttemptAt] = if (resetCursor) COMPLETED_NEXT_ATTEMPT else now
            it[VoucherPoolWorkerClaimTable.revision] = VoucherPoolWorkerClaimTable.revision + 1
        }
        check(updated == 1)
        return checkNotNull(lockSnapshot(claim.tenantId, claim.kind, claim.scopeId))
    }

    /**
     * requireCurrentOwner은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을
     * 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun requireCurrentOwner(connection: Connection, claim: WorkerClaim): WorkerClaimSnapshot {
        val current = lockSnapshot(claim.tenantId, claim.kind, claim.scopeId)
            ?: throw StaleWorkerClaimException()
        val now = transactionTime(connection)
        val identityIsStale = current.owner != claim.owner || current.revision != claim.revision
        val leaseIsStale = current.claimUntil == null || current.claimUntil <= now
        if (identityIsStale || leaseIsStale) throw StaleWorkerClaimException()

        return current
    }

    /**
     * boundedBackoff은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param attempt 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun boundedBackoff(attempt: Int): Long {
        val exponential = 1L shl (attempt - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        return minOf(exponential, policy.maximumBackoff.seconds)
    }

    /**
     * insertClaimIfMissing은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
     * 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param scopeId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun insertClaimIfMissing(
        tenantId: String,
        kind: WorkerKind,
        scopeId: UUID,
    ): Boolean = VoucherPoolWorkerClaimTable.insertIgnore {
        it[VoucherPoolWorkerClaimTable.tenantId] = tenantId
        it[VoucherPoolWorkerClaimTable.workerType] = kind.name
        it[VoucherPoolWorkerClaimTable.scopeId] = scopeId
    }.insertedCount == 1

    /**
     * lockSnapshot은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param scopeId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun lockSnapshot(
        tenantId: String,
        kind: WorkerKind,
        scopeId: UUID,
    ): WorkerClaimSnapshot? =
        selectSnapshot(tenantId, kind, scopeId, forUpdate = true)

    /**
     * selectSnapshot은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지
     * 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param scopeId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param forUpdate 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun selectSnapshot(
        tenantId: String,
        kind: WorkerKind,
        scopeId: UUID,
        forUpdate: Boolean,
    ): WorkerClaimSnapshot? {
        val query = VoucherPoolWorkerClaimTable
            .selectAll()
            .where {
                (VoucherPoolWorkerClaimTable.tenantId eq tenantId) and
                        (VoucherPoolWorkerClaimTable.workerType eq kind.name) and
                        (VoucherPoolWorkerClaimTable.scopeId eq scopeId)
            }
        if (forUpdate) query.forUpdate()
        return query.singleOrNull()?.workerClaimSnapshot()
    }

    /** worker CAS의 모든 deadline이 PostgreSQL transaction snapshot과 같은 시각을 보도록 raw 조회를 유지합니다. */
    /** worker CAS의 모든 deadline이 PostgreSQL transaction snapshot과 같은 시각을 보도록 raw 조회를 유지합니다. */
    private fun transactionTime(connection: Connection): Instant =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT transaction_timestamp()")
                .use { result ->
                    result.next()
                    result.getTimestamp(1).toInstant()
                }
        }

    /**
     * ownedClaim은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param deadline 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun WorkerClaimSnapshot.ownedClaim(deadline: Instant): WorkerClaim =
        WorkerClaim(
            tenantId = tenantId,
            kind = kind,
            scopeId = scopeId,
            owner = checkNotNull(owner),
            claimUntil = checkNotNull(claimUntil),
            cursor = cursor,
            attempt = attempt,
            nextAttemptAt = nextAttemptAt,
            checkpoint = checkpoint,
            revision = revision,
            runDeadline = deadline,
        )

    /**
     * workerClaimSnapshot은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
     * 거절합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun ResultRow.workerClaimSnapshot(): WorkerClaimSnapshot =
        WorkerClaimSnapshot(
            tenantId = this[VoucherPoolWorkerClaimTable.tenantId],
            kind = WorkerKind.valueOf(this[VoucherPoolWorkerClaimTable.workerType]),
            scopeId = this[VoucherPoolWorkerClaimTable.scopeId],
            owner = this[VoucherPoolWorkerClaimTable.ownerId],
            claimUntil = this[VoucherPoolWorkerClaimTable.claimUntil],
            cursor = this[VoucherPoolWorkerClaimTable.cursor],
            attempt = this[VoucherPoolWorkerClaimTable.attempt],
            nextAttemptAt = this[VoucherPoolWorkerClaimTable.nextAttemptAt],
            checkpoint = this[VoucherPoolWorkerClaimTable.checkpoint],
            poisonReason = this[VoucherPoolWorkerClaimTable.poisonReason],
            revision = this[VoucherPoolWorkerClaimTable.revision],
        )

    /**
     * currentConnection은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun currentConnection(): Connection =
        TransactionManager.currentOrNull()
            ?.connection
            ?.connection as? Connection ?: error("voucher pool worker repository requires an active JDBC transaction")
}
