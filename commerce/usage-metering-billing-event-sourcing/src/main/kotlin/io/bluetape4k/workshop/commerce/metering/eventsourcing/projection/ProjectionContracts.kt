package io.bluetape4k.workshop.commerce.metering.eventsourcing.projection

import java.time.Instant
import java.util.*

/**
 * ProjectionGenerationState은 사용량 과금 이벤트 소싱 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
enum class ProjectionGenerationState {
    /**
     * `ProjectionGenerationState.ACTIVE`은 ProjectionGenerationState 업무 상태의 `ACTIVE` 단계입니다. 허용된 상태
     * 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    ACTIVE,
    /**
     * `ProjectionGenerationState.BUILDING`은 ProjectionGenerationState 업무 상태의 `BUILDING` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    BUILDING,
    /**
     * `ProjectionGenerationState.FAILED`은 ProjectionGenerationState 업무 상태의 `FAILED` 단계입니다. 허용된 상태
     * 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    FAILED,
    /**
     * `ProjectionGenerationState.RETIRED`은 ProjectionGenerationState 업무 상태의 `RETIRED` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    RETIRED,
}

/**
 * ProjectionTelemetry은 사용량 과금 이벤트 소싱 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
interface ProjectionTelemetry {
    /**
     * recordRebuild은 사용량 과금 이벤트 소싱 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param outcome 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun recordRebuild(outcome: String)
}

/**
 * ProjectionGenerationTransitions은 사용량 과금 이벤트 소싱 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을
 * 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
object ProjectionGenerationTransitions {
    /**
     * allows은 사용량 과금 이벤트 소싱 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param from 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param to 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun allows(from: ProjectionGenerationState, to: ProjectionGenerationState): Boolean = when (from) {
        ProjectionGenerationState.ACTIVE  -> to == ProjectionGenerationState.RETIRED
        ProjectionGenerationState.BUILDING ->
            to == ProjectionGenerationState.ACTIVE || to == ProjectionGenerationState.FAILED
        ProjectionGenerationState.FAILED  -> to == ProjectionGenerationState.RETIRED
        ProjectionGenerationState.RETIRED -> to == ProjectionGenerationState.ACTIVE
    }
}

/**
 * ProjectionGeneration은 사용량 과금 이벤트 소싱 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
data class ProjectionGeneration(
    /**
     * `projectionName`은 사용량 과금·정산에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val projectionName: String,
    /**
     * `generation`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generation: Int,
    /**
     * `state`은 사용량 과금·정산에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: ProjectionGenerationState,
    /**
     * `checkpoint`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val checkpoint: Long,
    /**
     * `highWatermark`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val highWatermark: Long,
    /**
     * `ownerToken`은 사용량 과금·정산에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerToken: UUID?,
    /**
     * `leaseUntil`은 사용량 과금·정산에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val leaseUntil: Instant?,
    /**
     * `failedPosition`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val failedPosition: Long?,
    /**
     * `failureDigest`은 사용량 과금·정산에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val failureDigest: String?,
)

/**
 * ProjectionLease은 사용량 과금 이벤트 소싱 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
data class ProjectionLease(
    /**
     * `projectionName`은 사용량 과금·정산에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val projectionName: String,
    /**
     * `generation`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generation: Int,
    /**
     * `ownerToken`은 사용량 과금·정산에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerToken: UUID,
    /**
     * `leaseUntil`은 사용량 과금·정산에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val leaseUntil: Instant,
)

/**
 * ProjectionApplyResult은 사용량 과금 이벤트 소싱 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
enum class ProjectionApplyResult {
    /**
     * `ProjectionApplyResult.APPLIED`은 ProjectionApplyResult 업무 상태의 `APPLIED` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답
     * 분기를 선택하는 기준으로 사용합니다.
     */
    APPLIED,
    /**
     * `ProjectionApplyResult.ALREADY_APPLIED`은 ProjectionApplyResult 업무 상태의 `ALREADY_APPLIED`
     * 단계입니다. 허용된
     * 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    ALREADY_APPLIED,
}

/**
 * StaleProjectionOwnerException은 사용량 과금 이벤트 소싱 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
class StaleProjectionOwnerException(name: String, generation: Int):
    IllegalStateException("stale_projection_owner:$name:$generation")
