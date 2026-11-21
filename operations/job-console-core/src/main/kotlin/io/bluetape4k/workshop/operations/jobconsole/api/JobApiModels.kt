package io.bluetape4k.workshop.operations.jobconsole.api

import com.fasterxml.jackson.annotation.JsonValue
import io.bluetape4k.ToStringBuilder
import io.bluetape4k.support.hashOf
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/**
 * 데모 worker가 실행할 결정론적 업무 종류입니다.
 *
 * HTTP JSON에서는 `wireValue`가 사용됩니다. 새 업무를 추가할 때는 worker가
 * 처리할 단위와 ETA 표본의 의미를 함께 정의해야 하며, 임의의 사용자 입력을
 * enum 이름으로 해석하지 않습니다.
 * 예:
 * ```json
 * {"jobType":"document_export","workUnits":3,"failureMode":"none"}
 * ```
 * @property wireValue 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
enum class JobType(@get:JsonValue val wireValue: String) {
    /**
     * `JobType.DOCUMENT_EXPORT`은 `document export` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event routing 키를
     * 결정합니다.
     */
    DOCUMENT_EXPORT("document_export"),
    /**
     * `JobType.REPORT_GENERATION`은 `report generation` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event
     * routing 키를 결정합니다.
     */
    REPORT_GENERATION("report_generation"),
}

/**
 * 외부 provider 없이 retry와 terminal failure를 재현하는 결정론적 fixture 모드입니다.
 *
 * `RETRY_ONCE`는 첫 attempt만 실패시키고, `ALWAYS_RETRYABLE`은 retry budget을
 * 소진시키며, `NON_RETRYABLE`은 즉시 `failed`로 종료합니다. 운영 코드에서
 * provider의 실제 오류를 이 enum으로 대체하는 것이 아니라 테스트 시나리오의
 * 입력으로만 사용합니다.
 * 예: `SubmitJobRequest(JobType.DOCUMENT_EXPORT, 3, FailureMode.RETRY_ONCE)`
 * @property wireValue 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
enum class FailureMode(@get:JsonValue val wireValue: String) {
    /**
     * `FailureMode.NONE`은 `none` 업무 상황을 나타냅니다. provider 또는 실행 시나리오를 선택하며 실제 업무 상태와 혼동하지 않습니다.
     */
    NONE("none"),
    /**
     * `FailureMode.RETRY_ONCE`은 `retry once` 업무 상황을 나타냅니다. provider 또는 실행 시나리오를 선택하며 실제 업무 상태와
     * 혼동하지 않습니다.
     */
    RETRY_ONCE("retry_once"),
    /**
     * `FailureMode.NON_RETRYABLE`은 입력 또는 영구 오류로 판단되어 같은 요청을 반복해도 성공하지 않는 값입니다.
     */
    NON_RETRYABLE("non_retryable"),
    /**
     * `FailureMode.ALWAYS_RETRYABLE`은 `always retryable` 업무 상황을 나타냅니다. provider 또는 실행 시나리오를 선택하며
     * 실제 업무 상태와
     * 혼동하지 않습니다.
     */
    ALWAYS_RETRYABLE("always_retryable"),
}

/**
 * ETA 표본의 충분함을 나타냅니다. `INSUFFICIENT_DATA`는 계산 실패가 아니라
 * 정직한 추정치를 만들 수 없다는 결과이며, 호출자는 이를 SLA나 확정 시각으로
 * 표시하면 안 됩니다.
 *
 * 예: `QueueProjection(..., confidence = EtaConfidence.INSUFFICIENT_DATA, sampleSize = 0, ...)`
 * @property wireValue 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
enum class EtaConfidence(@get:JsonValue val wireValue: String) {
    /**
     * `EtaConfidence.HIGH`은 `high` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    HIGH("high"),
    /**
     * `EtaConfidence.MEDIUM`은 `medium` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    MEDIUM("medium"),
    /**
     * `EtaConfidence.LOW`은 `low` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    LOW("low"),
    /**
     * `EtaConfidence.INSUFFICIENT_DATA`은 `insufficient data` 업무 상황을 나타냅니다. 현재 aggregate의 revision과
     * 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    INSUFFICIENT_DATA("insufficient_data"),
}

/**
 * 결정론적 worker에 제출하는 닫힌 request 계약입니다.
 *
 * `workUnits`는 worker가 처리할 checkpoint 개수이고, `failureMode`는 장애
 * 실습 시나리오입니다. 같은 request를 같은 `Idempotency-Key`로 재전송하려면
 * 모든 필드를 그대로 유지해야 합니다.
 * 예:
 * ```kotlin
 * val request = SubmitJobRequest(
 *     jobType = JobType.DOCUMENT_EXPORT,
 *     workUnits = 3,
 *     failureMode = FailureMode.RETRY_ONCE,
 * )
 * ```
 * @property jobType 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property workUnits 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property failureMode 현재 운영 작업 콘솔 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 */
data class SubmitJobRequest(
    /**
     * `jobType`은 작업 운영 콘솔에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val jobType: JobType,
    /**
     * `workUnits`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val workUnits: Int,
    /**
     * `failureMode`은 작업 운영 콘솔에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val failureMode: FailureMode = FailureMode.NONE,
) : Serializable {
    companion object {
        /**
         * `serialVersionUID`은 작업 운영 콘솔에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * ETA의 earliest/latest 경계를 표현하는 시간 범위입니다.
 * @property earliest 운영 작업 콘솔의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property latest 운영 작업 콘솔의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을 유지합니다.
 */
data class TimeRange(
    /**
     * `earliest`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val earliest: Instant,
    /**
     * `latest`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val latest: Instant,
) : Serializable {
    companion object {
        /**
         * `serialVersionUID`은 작업 운영 콘솔에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 호출자에게 공개할 tenant-local queue와 ETA projection입니다.
 *
 * `position`과 `jobsAhead`는 같은 tenant 내부에서만 계산하며, `queueVersion`은
 * SSE 알림 뒤 새 REST snapshot을 읽었는지 판단하는 단조 증가 버전입니다.
 * `estimated*Range`가 null이면 표본 부족으로 추정치를 제공하지 않는다는 뜻입니다.
 * 예: `queue?.confidence == EtaConfidence.INSUFFICIENT_DATA`이면 UI는 ETA 대신
 * “표본 수집 중”을 표시해야 합니다.
 * @property position 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property jobsAhead 운영 작업 콘솔 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property estimatedStartRange 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property estimatedCompletionRange 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야
 * 합니다.
 * @property confidence 현재 운영 작업 콘솔 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 * @property sampleSize 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property queueVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property updatedAt 운영 작업 콘솔의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */
data class QueueProjection(
    /**
     * `position`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val position: Int,
    /**
     * `jobsAhead`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val jobsAhead: Int,
    /**
     * `estimatedStartRange`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val estimatedStartRange: TimeRange?,
    /**
     * `estimatedCompletionRange`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val estimatedCompletionRange: TimeRange?,
    /**
     * `confidence`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val confidence: EtaConfidence,
    /**
     * `sampleSize`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val sampleSize: Int,
    /**
     * `queueVersion`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val queueVersion: Long,
    /**
     * `updatedAt`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val updatedAt: Instant,
) : Serializable {
    companion object {
        /**
         * `serialVersionUID`은 작업 운영 콘솔에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 두 HTTP adapter가 공유하는 redacted REST source-of-truth snapshot입니다.
 *
 * tenant/submitter, payload, idempotency key를 포함하지 않으므로 브라우저나
 * 운영자 화면에 그대로 전달할 수 있습니다. SSE event를 받은 뒤에도 이
 * snapshot을 다시 읽어야 하며 event payload를 현재 상태로 간주하면 안 됩니다.
 * 예: `GET /v1/jobs/{jobId}`의 응답은 `state`, `progress`, `checkpoint`, `queue`
 * 를 제공하지만 caller identity는 제공하지 않습니다.
 * @property jobId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property jobType 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property state 현재 운영 작업 콘솔 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property progress 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property checkpoint 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property queue 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property version 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property updatedAt 운영 작업 콘솔의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */
data class JobSnapshot(
    /**
     * `jobId`은 작업 운영 콘솔에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val jobId: UUID,
    /**
     * `jobType`은 작업 운영 콘솔에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val jobType: JobType,
    /**
     * `state`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: JobState,
    /**
     * `progress`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val progress: Int,
    /**
     * `checkpoint`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val checkpoint: Long?,
    /**
     * `queue`은 작업 운영 콘솔에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val queue: QueueProjection?,
    /**
     * `version`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val version: Long,
    /**
     * `updatedAt`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val updatedAt: Instant,
) : Serializable {
    companion object {
        /**
         * `serialVersionUID`은 작업 운영 콘솔에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * HTTP adapter가 wire detail을 더하기 전의 멱등성 제출 결과입니다.
 *
 * `OwnerCompleted`는 최초 owner가 durable row와 snapshot을 commit한 결과이고,
 * `Replayed`는 같은 canonical request가 저장된 snapshot을 재생한 결과입니다.
 * 두 결과의 snapshot은 같아야 하며 adapter는 replay 여부만 header로 표시합니다.
 * 예:
 * ```kotlin
 * when (val outcome = service.submit(scope, "export-1", request)) {
 *     is JobSubmissionOutcome.OwnerCompleted -> save(outcome.snapshot)
 *     is JobSubmissionOutcome.Replayed -> useSavedResponse(outcome.snapshot)
 *     JobSubmissionOutcome.Conflict -> fixRequestOrKey()
 *     else -> retrySameKey()
 * }
 * ```
 */
sealed interface JobSubmissionOutcome : Serializable {
/**
 * OwnerCompleted은 운영 작업 콘솔에서 전달되는 불변 데이터 모델입니다.
 * @property snapshot 운영 작업 콘솔 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property responseHeaders 운영 작업 콘솔 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한
 * 정보만 포함합니다.
 */

    data class OwnerCompleted(
        /**
         * `snapshot`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val snapshot: JobSnapshot,
        /**
         * `responseHeaders`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        val responseHeaders: Map<String, List<String>> = emptyMap(),
    ) : JobSubmissionOutcome
/**
 * Replayed은 운영 작업 콘솔에서 전달되는 불변 데이터 모델입니다.
 * @property snapshot 운영 작업 콘솔 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property responseHeaders 운영 작업 콘솔 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한
 * 정보만 포함합니다.
 */


    data class Replayed(
        /**
         * `snapshot`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val snapshot: JobSnapshot,
        /**
         * `responseHeaders`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        val responseHeaders: Map<String, List<String>> = emptyMap(),
    ) : JobSubmissionOutcome

    /**
     * Conflict은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object Conflict : JobSubmissionOutcome

    /**
     * InFlightTimeout은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object InFlightTimeout : JobSubmissionOutcome

    /**
     * WaiterOverflow은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object WaiterOverflow : JobSubmissionOutcome

    /**
     * Abandoned은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object Abandoned : JobSubmissionOutcome
}

/**
 * 제출 결과로부터 조립한 framework-neutral HTTP response입니다.
 *
 * Spring과 Ktor adapter가 같은 status/body/content type을 반환하도록 하며,
 * `replayed`는 body에 섞지 않고 adapter가 `Idempotency-Replayed` header로
 * 표현할 수 있게 합니다. `body`는 호출자가 소유하는 immutable snapshot으로
 * 취급해야 합니다.
 * @property status 현재 운영 작업 콘솔 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property body 운영 작업 콘솔 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 * @property contentType 운영 작업 콘솔 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property headers 운영 작업 콘솔 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 * @property replayed 운영 작업 콘솔 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 */
data class JobSubmissionHttpResponse(
    /**
     * `status`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: Int,
    /**
     * `body`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val body: ByteArray,
    /**
     * `contentType`은 작업 운영 콘솔에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val contentType: String,
    /**
     * `headers`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val headers: Map<String, List<String>> = emptyMap(),
    /**
     * `replayed`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val replayed: Boolean,
) : Serializable {
    init {
        status.requireInRange(100, 599, "status")
        contentType.requireNotBlank("contentType")
    }

    /**
     * equals은 운영 작업 콘솔의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param other 업무 처리에 필요한 other 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun equals(other: Any?): Boolean =
        other is JobSubmissionHttpResponse &&
            status == other.status &&
            body.contentEquals(other.body) &&
            contentType == other.contentType &&
            headers == other.headers &&
            replayed == other.replayed

    /**
     * hashCode은 운영 작업 콘솔의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun hashCode(): Int = hashOf(status, body.contentHashCode(), contentType, headers, replayed)

    /**
     * toString은 운영 작업 콘솔의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun toString(): String =
        ToStringBuilder(this)
            .add("status", status)
            .add("bodySize", body.size)
            .add("contentType", contentType)
            .add("headers", headers)
            .add("replayed", replayed)
            .toString()
}

/**
 * 안정적인 redacted problem payload입니다.
 *
 * `code`는 호출자가 재시도·수정 여부를 결정하는 계약이고, 내부 exception이나
 * stack trace는 포함하지 않습니다. `retryAfterSeconds`가 있으면 adapter는
 * `Retry-After` header도 같은 값으로 반환합니다.
 * 예: `IDEMPOTENCY_IN_FLIGHT`는 `409`와 `Retry-After: 1`을 함께 사용합니다.
 * @property status 현재 운영 작업 콘솔 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property code 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property title 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property requestId 요청 추적 식별자입니다. API 오류·로그·이벤트를 하나의 업무 시도로 연결하되 비즈니스 식별자와 혼동하지 않습니다.
 * @property retryAfterSeconds 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
data class JobProblem(
    /**
     * `status`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: Int,
    /**
     * `code`은 작업 운영 콘솔에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: JobProblemCode,
    /**
     * `title`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val title: String,
    /**
     * `requestId`은 작업 운영 콘솔에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val requestId: String,
    /**
     * `retryAfterSeconds`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val retryAfterSeconds: Long? = null,
) : Serializable {
    companion object {
        /**
         * `serialVersionUID`은 작업 운영 콘솔에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * SSE가 전달하는 notification 종류입니다. event는 상태 payload가 아니라
 * snapshot refresh 힌트이므로 수신 뒤 `GET /v1/jobs/{jobId}`를 다시 호출합니다.
 * @property wireValue 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
enum class JobEventType(@get:JsonValue val wireValue: String) {
    /**
     * `JobEventType.JOB_UPDATED`은 `job updated` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event routing 키를
     * 결정합니다.
     */
    JOB_UPDATED("job.updated"),
    /**
     * `JobEventType.QUEUE_UPDATED`은 `queue updated` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event routing
     * 키를 결정합니다.
     */
    QUEUE_UPDATED("queue.updated"),
    /**
     * `JobEventType.HEARTBEAT`은 `heartbeat` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event routing 키를 결정합니다.
     */
    HEARTBEAT("heartbeat"),
}

/**
 * notification-only SSE envelope입니다. payload, raw identity, stack trace를
 * 노출하지 않으며 `eventId`와 `queueVersion`으로 새로고침 필요성을 식별합니다.
 *
 * 예:
 * ```text
 * event: job.updated
 * id: 018f1f2e-3d4c-7b6a-8f90-1234567890ab
 * data: {"jobId":"...","queueVersion":7}
 * ```
 * @property eventId 이벤트의 불변 식별자입니다. inbox/outbox 중복 제거와 감사 추적의 기준이므로 재시도에도 같은 값을 유지합니다.
 * @property eventType 운영 작업 콘솔 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property jobId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property queueVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property occurredAt 운영 작업 콘솔의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */
data class JobEvent(
    /**
     * `eventId`은 작업 운영 콘솔에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val eventId: UUID,
    /**
     * `eventType`은 작업 운영 콘솔에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val eventType: JobEventType,
    /**
     * `jobId`은 작업 운영 콘솔에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val jobId: UUID,
    /**
     * `queueVersion`은 작업 운영 콘솔에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val queueVersion: Long,
    /**
     * `occurredAt`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val occurredAt: Instant,
) : Serializable {
    companion object {
        /**
         * `serialVersionUID`은 작업 운영 콘솔에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
