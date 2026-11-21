package io.bluetape4k.workshop.operations.jobconsole.domain

import com.fasterxml.jackson.annotation.JsonValue
import java.time.Instant
import java.util.UUID

/**
 * PostgreSQL job row가 가질 수 있는 닫힌 상태 집합입니다.
 *
 * `QUEUED`는 아직 claim되지 않은 상태, `RUNNING`은 lease를 보유한 worker가
 * 처리 중인 상태, `CANCEL_REQUESTED`는 running worker가 다음 안전한 checkpoint에서
 * 취소를 반영해야 하는 상태입니다. 나머지는 terminal 상태이며 이후 signal을
 * 허용하지 않습니다.
 * @property wireValue 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
enum class JobState(@get:JsonValue val wireValue: String) {
    /**
     * `JobState.QUEUED`은 `queued` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를 확인합니다.
     */
    QUEUED("queued"),
    /**
     * `JobState.RUNNING`은 `running` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를 확인합니다.
     */
    RUNNING("running"),
    /**
     * `JobState.CANCEL_REQUESTED`은 `cancel requested` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal
     * 여부를 확인합니다.
     */
    CANCEL_REQUESTED("cancel_requested"),
    /**
     * `JobState.SUCCEEDED`은 외부 provider 또는 내부 작업의 성공 결과가 확정된 값입니다.
     */
    SUCCEEDED("succeeded"),
    /**
     * `JobState.FAILED`은 처리가 실패했으며 재시도 가능 여부를 별도 정책으로 판단해야 하는 값입니다.
     */
    FAILED("failed"),
    /**
     * `JobState.DEAD_LETTERED`은 `dead lettered` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를
     * 확인합니다.
     */
    DEAD_LETTERED("dead_lettered"),
    /**
     * `JobState.CANCELLED`은 취소 요청과 필요한 보상 작업이 반영되어 더 이상 정상 진행하지 않는 값입니다.
     */
    CANCELLED("cancelled"),
    ;
    /**
     * 운영 작업 콘솔 정책을 선택하는 플래그입니다. true/false별 처리 경로와 기본값을 계약으로 유지합니다.
     */

    val terminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == DEAD_LETTERED || this == CANCELLED
    /**
     * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    val active: Boolean
        get() = this == RUNNING || this == CANCEL_REQUESTED
}

/** 상태 전이를 요청하는 내부 signal입니다. */
/** 상태 전이를 요청하는 내부 signal입니다. */
enum class JobSignal {
    /**
     * `JobSignal.CLAIM`은 `claim` 업무 상황을 나타냅니다. 현재 상태와 aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    CLAIM,
    /**
     * `JobSignal.CANCEL`은 `cancel` 업무 상황을 나타냅니다. 현재 상태와 aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    CANCEL,
    /**
     * `JobSignal.CHECKPOINT`은 `checkpoint` 업무 상황을 나타냅니다. 현재 상태와 aggregate revision을 검증한 뒤 허용된 전이만
     * 실행합니다.
     */
    CHECKPOINT,
    /**
     * `JobSignal.SUCCESS`은 `success` 업무 상황을 나타냅니다. 현재 상태와 aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    SUCCESS,
    /**
     * `JobSignal.RETRYABLE_FAILURE`은 `retryable failure` 업무 상황을 나타냅니다. 현재 상태와 aggregate revision을
     * 검증한 뒤
     * 허용된 전이만 실행합니다.
     */
    RETRYABLE_FAILURE,
    /**
     * `JobSignal.RETRY_EXHAUSTED`은 `retry exhausted` 업무 상황을 나타냅니다. 현재 상태와 aggregate revision을 검증한
     * 뒤 허용된
     * 전이만 실행합니다.
     */
    RETRY_EXHAUSTED,
    /**
     * `JobSignal.NON_RETRYABLE_FAILURE`은 `non retryable failure` 업무 상황을 나타냅니다. 현재 상태와 aggregate
     * revision을
     * 검증한 뒤 허용된 전이만 실행합니다.
     */
    NON_RETRYABLE_FAILURE,
}

/**
 * HTTP와 운영 로그에서 사용할 stable problem code입니다. 내부 예외 메시지 대신
 * 이 값으로 호출자 동작을 결정하며, `wireValue`는 JSON 계약입니다.
 * @property wireValue 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
enum class JobProblemCode(@get:JsonValue val wireValue: String) {
    /**
     * `JobProblemCode.VALIDATION_FAILED`은 `validation failed` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의
     * 분기 기준으로 사용합니다.
     */
    VALIDATION_FAILED("validation_failed"),
    /**
     * `JobProblemCode.INVALID_IDEMPOTENCY_REQUEST`은 `invalid idempotency request` 업무 상황을 나타냅니다.
     * 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    INVALID_IDEMPOTENCY_REQUEST("invalid_idempotency_request"),
    /**
     * `JobProblemCode.IDEMPOTENCY_REQUEST_TOO_LARGE`은 `idempotency request too large` 업무 상황을
     * 나타냅니다. 호출자는 이
     * 코드를 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    IDEMPOTENCY_REQUEST_TOO_LARGE("idempotency_request_too_large"),
    /**
     * `JobProblemCode.IDEMPOTENCY_KEY_REUSED`은 `idempotency key reused` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    IDEMPOTENCY_KEY_REUSED("idempotency_key_reused"),
    /**
     * `JobProblemCode.IDEMPOTENCY_IN_FLIGHT`은 `idempotency in flight` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    IDEMPOTENCY_IN_FLIGHT("idempotency_in_flight"),
    /**
     * `JobProblemCode.IDEMPOTENCY_WAITERS_EXCEEDED`은 `idempotency waiters exceeded` 업무 상황을 나타냅니다.
     * 호출자는 이
     * 코드를 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    IDEMPOTENCY_WAITERS_EXCEEDED("idempotency_waiters_exceeded"),
    /**
     * `JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED`은 `idempotency snapshot rejected` 업무 상황을
     * 나타냅니다. 호출자는 이
     * 코드를 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    IDEMPOTENCY_SNAPSHOT_REJECTED("idempotency_snapshot_rejected"),
    /**
     * `JobProblemCode.JOB_NOT_FOUND`은 `job not found` 업무 상황을 나타냅니다. 호출자는 이 코드를 재시도·거절·대사·운영자 조치의
     * 분기 기준으로
     * 사용합니다.
     */
    JOB_NOT_FOUND("job_not_found"),
    /**
     * `JobProblemCode.SCOPE_DENIED`은 `scope denied` 업무 상황을 나타냅니다. 호출자는 이 코드를 재시도·거절·대사·운영자 조치의 분기
     * 기준으로 사용합니다.
     */
    SCOPE_DENIED("scope_denied"),
    /**
     * `JobProblemCode.INVALID_TRANSITION`은 `invalid transition` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의
     * 분기 기준으로 사용합니다.
     */
    INVALID_TRANSITION("invalid_transition"),
    /**
     * `JobProblemCode.STALE_REVISION`은 `stale revision` 업무 상황을 나타냅니다. 호출자는 이 코드를 재시도·거절·대사·운영자 조치의
     * 분기 기준으로
     * 사용합니다.
     */
    STALE_REVISION("stale_revision"),
    /**
     * `JobProblemCode.DEPENDENCY_UNAVAILABLE`은 `dependency unavailable` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    DEPENDENCY_UNAVAILABLE("dependency_unavailable"),
    /**
     * `JobProblemCode.LEASE_LOST`은 `lease lost` 업무 상황을 나타냅니다. 호출자는 이 코드를 재시도·거절·대사·운영자 조치의 분기 기준으로
     * 사용합니다.
     */
    LEASE_LOST("lease_lost"),
}

/**
 * worker가 보유한 lease와 fencing 정보입니다.
 *
 * 모든 checkpoint/terminal write는 `token`, `attempt`, `revision`, `expiresAt`
 * 를 PostgreSQL의 현재 row와 비교해 stale worker의 commit을 차단합니다.
 * @property jobId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property token 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property attempt 같은 업무를 재시도한 횟수 또는 다음 재시도까지의 대기 정책입니다. 최대 시도 횟수와 terminal failure 규칙을 함께 적용합니다.
 * @property expiresAt 운영 작업 콘솔의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */
data class JobLease(
    /**
     * `jobId`은 작업 운영 콘솔에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val jobId: UUID,
    /**
     * `token`은 작업 운영 콘솔에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val token: UUID,
    /**
     * `attempt`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val attempt: Int,
    /**
     * `expiresAt`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expiresAt: Instant,
    /**
     * `revision`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
)

/**
 * 닫힌 상태 전이표를 위반하거나 stale revision을 사용했을 때 발생합니다.
 * @property code 운영 작업 콘솔의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */
class InvalidJobTransition(
    /**
     * `code`은 작업 운영 콘솔에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: JobProblemCode,
    message: String,
): IllegalStateException(message)
