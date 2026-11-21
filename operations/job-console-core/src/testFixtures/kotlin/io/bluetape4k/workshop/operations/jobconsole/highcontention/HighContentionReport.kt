package io.bluetape4k.workshop.operations.jobconsole.highcontention

import com.fasterxml.jackson.annotation.JsonProperty
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.time.Instant

/**
 * `HighContentionTerminalStatus`은 상태 코드입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 */
enum class HighContentionTerminalStatus {
    @JsonProperty("PASS")
    /**
     * `HighContentionTerminalStatus.PASS`은 `pass` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를
     * 확인합니다.
     */
    PASS,

    @JsonProperty("FAIL")
    /**
     * `HighContentionTerminalStatus.FAIL`은 `fail` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를
     * 확인합니다.
     */
    FAIL,

    @JsonProperty("ERROR")
    /**
     * `HighContentionTerminalStatus.ERROR`은 `error` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를
     * 확인합니다.
     */
    ERROR,

    @JsonProperty("UNAVAILABLE")
    /**
     * `HighContentionTerminalStatus.UNAVAILABLE`은 `unavailable` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와
     * terminal 여부를 확인합니다.
     */
    UNAVAILABLE,
}

/**
 * `HighContentionCorrectness`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
enum class HighContentionCorrectness {
    @JsonProperty("PASS")
    /**
     * `HighContentionCorrectness.PASS`은 `pass` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤
     * 사용합니다.
     */
    PASS,

    @JsonProperty("FAIL")
    /**
     * `HighContentionCorrectness.FAIL`은 `fail` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤
     * 사용합니다.
     */
    FAIL,

    @JsonProperty("NOT_EVALUATED")
    /**
     * `HighContentionCorrectness.NOT_EVALUATED`은 `not evaluated` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    NOT_EVALUATED,
}

/**
 * `HighContentionErrorCode`은 업무 코드입니다.
 * 호출자가 재시도·거절·대사·운영자 조치를 분기하는 오류 계약입니다.
 */
enum class HighContentionErrorCode {
    @JsonProperty("NONE")
    /**
     * `HighContentionErrorCode.NONE`은 `none` 업무 상황을 나타냅니다. 호출자는 이 코드를 재시도·거절·대사·운영자 조치의 분기 기준으로
     * 사용합니다.
     */
    NONE,

    @JsonProperty("INVALID_PROFILE")
    /**
     * `HighContentionErrorCode.INVALID_PROFILE`은 `invalid profile` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    INVALID_PROFILE,

    @JsonProperty("INVALID_REALIZATION")
    /**
     * `HighContentionErrorCode.INVALID_REALIZATION`은 `invalid realization` 업무 상황을 나타냅니다. 호출자는 이
     * 코드를 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    INVALID_REALIZATION,

    @JsonProperty("KEY_SCOPE_VIOLATION")
    /**
     * `HighContentionErrorCode.KEY_SCOPE_VIOLATION`은 `key scope violation` 업무 상황을 나타냅니다. 호출자는 이
     * 코드를 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    KEY_SCOPE_VIOLATION,

    @JsonProperty("EXECUTION_ERROR")
    /**
     * `HighContentionErrorCode.EXECUTION_ERROR`은 `execution error` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    EXECUTION_ERROR,

    @JsonProperty("INJECTION_TIMEOUT")
    /**
     * `HighContentionErrorCode.INJECTION_TIMEOUT`은 `injection timeout` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    INJECTION_TIMEOUT,

    @JsonProperty("FAILURE_DETECTION_TIMEOUT")
    /**
     * `HighContentionErrorCode.FAILURE_DETECTION_TIMEOUT`은 `failure detection timeout` 업무 상황을
     * 나타냅니다. 호출자는 이 코드를 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    FAILURE_DETECTION_TIMEOUT,

    @JsonProperty("WORKLOAD_TIMEOUT")
    /**
     * `HighContentionErrorCode.WORKLOAD_TIMEOUT`은 `workload timeout` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    WORKLOAD_TIMEOUT,

    @JsonProperty("RECOVERY_TIMEOUT")
    /**
     * `HighContentionErrorCode.RECOVERY_TIMEOUT`은 `recovery timeout` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    RECOVERY_TIMEOUT,

    @JsonProperty("CLEANUP_TIMEOUT")
    /**
     * `HighContentionErrorCode.CLEANUP_TIMEOUT`은 `cleanup timeout` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    CLEANUP_TIMEOUT,

    @JsonProperty("REPORT_SERIALIZATION")
    /**
     * `HighContentionErrorCode.REPORT_SERIALIZATION`은 `report serialization` 업무 상황을 나타냅니다. 호출자는 이
     * 코드를 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    REPORT_SERIALIZATION,

    @JsonProperty("JOURNAL_ERROR")
    /**
     * `HighContentionErrorCode.JOURNAL_ERROR`은 `journal error` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    JOURNAL_ERROR,

    @JsonProperty("PARENT_CLEANUP_ERROR")
    /**
     * `HighContentionErrorCode.PARENT_CLEANUP_ERROR`은 `parent cleanup error` 업무 상황을 나타냅니다. 호출자는 이
     * 코드를 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    PARENT_CLEANUP_ERROR,

    @JsonProperty("PREFLIGHT_UNAVAILABLE")
    /**
     * `HighContentionErrorCode.PREFLIGHT_UNAVAILABLE`은 `preflight unavailable` 업무 상황을 나타냅니다. 호출자는
     * 이 코드를 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    PREFLIGHT_UNAVAILABLE,
}

/**
 * HighContentionTerminalResult 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionTerminalResult(
/**
 * terminalStatus은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val terminalStatus: HighContentionTerminalStatus,
/**
 * correctness은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val correctness: HighContentionCorrectness,
/**
 * errorCode은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val errorCode: HighContentionErrorCode,
/**
 * failedPhase은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val failedPhase: String?,
/**
 * failedInvariantIds은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val failedInvariantIds: List<String>,
/**
 * redactedErrorClass은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val redactedErrorClass: String?,
) {
/**
 * validate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun validate(): HighContentionTerminalResult =
        apply {
            failedPhase?.let { HighContentionArtifactPaths.requireIdentifier(it, "failedPhase") }
            failedInvariantIds.forEach {
                HighContentionArtifactPaths.requireIdentifier(it, "failedInvariantId")
            }
            failedInvariantIds
                .distinct()
                .size
                .requireEquals(failedInvariantIds.size, "failedInvariantIds cardinality")
            redactedErrorClass?.requireNotBlank("redactedErrorClass")
            when (terminalStatus) {
                HighContentionTerminalStatus.PASS -> {
                    correctness.requireEquals(HighContentionCorrectness.PASS, "PASS correctness")
                    errorCode.requireEquals(HighContentionErrorCode.NONE, "PASS errorCode")
                    failedPhase.requireEquals(null, "PASS failedPhase")
                    failedInvariantIds.requireEquals(emptyList(), "PASS failedInvariantIds")
                    redactedErrorClass.requireEquals(null, "PASS redactedErrorClass")
                }

                HighContentionTerminalStatus.FAIL -> {
                    correctness.requireEquals(HighContentionCorrectness.FAIL, "FAIL correctness")
                    errorCode.requireEquals(HighContentionErrorCode.NONE, "FAIL errorCode")
                    failedInvariantIds.requireNotEmpty("FAIL failedInvariantIds")
                    redactedErrorClass.requireEquals(null, "FAIL redactedErrorClass")
                }

                HighContentionTerminalStatus.ERROR -> {
                    if (errorCode == HighContentionErrorCode.NONE) {
                        throw IllegalArgumentException("ERROR requires a non-NONE errorCode")
                    }
                    failedPhase.requireNotBlank("ERROR failedPhase")
                }

                HighContentionTerminalStatus.UNAVAILABLE -> {
                    correctness.requireEquals(
                        HighContentionCorrectness.NOT_EVALUATED,
                        "UNAVAILABLE correctness",
                    )
                    errorCode.requireEquals(
                        HighContentionErrorCode.PREFLIGHT_UNAVAILABLE,
                        "UNAVAILABLE errorCode",
                    )
                    failedPhase.requireNotBlank("UNAVAILABLE failedPhase")
                    failedInvariantIds.requireEquals(emptyList(), "UNAVAILABLE failedInvariantIds")
                }
            }
        }
}

/**
 * `HighContentionTerminalDisposition`은 후속 처리 판단입니다.
 * 외부 결과를 성공·재시도·보상·수동 대사로 분기할 때 사용하는 업무 계약입니다.
 */
enum class HighContentionTerminalDisposition {
    /**
     * `HighContentionTerminalDisposition.SUCCEEDED`은 외부 provider 또는 내부 작업의 성공 결과가 확정된 값입니다.
     */
    SUCCEEDED,
    /**
     * `HighContentionTerminalDisposition.FAILED_CLOSED`은 `failed closed` 업무 상황을 나타냅니다.
     * 성공·재시도·보상·수동 대사 중 하나로 후속 처리를 분기합니다.
     */
    FAILED_CLOSED,
    /**
     * `HighContentionTerminalDisposition.EXECUTION_FAILED`은 `execution failed` 업무 상황을 나타냅니다.
     * 성공·재시도·보상·수동 대사 중 하나로 후속 처리를 분기합니다.
     */
    EXECUTION_FAILED,
    /**
     * `HighContentionTerminalDisposition.LOCALLY_REJECTED`은 `locally rejected` 업무 상황을 나타냅니다.
     * 성공·재시도·보상·수동 대사 중 하나로 후속 처리를 분기합니다.
     */
    LOCALLY_REJECTED,
    /**
     * `HighContentionTerminalDisposition.CANCELLED`은 취소 요청과 필요한 보상 작업이 반영되어 더 이상 정상 진행하지 않는 값입니다.
     */
    CANCELLED,
    /**
     * `HighContentionTerminalDisposition.TIMED_OUT`은 `timed out` 업무 상황을 나타냅니다. 성공·재시도·보상·수동 대사 중
     * 하나로 후속 처리를 분기합니다.
     */
    TIMED_OUT,
    /**
     * `HighContentionTerminalDisposition.DUPLICATE_SUPPRESSED`은 `duplicate suppressed` 업무 상황을
     * 나타냅니다. 성공·재시도·보상·수동 대사 중 하나로 후속 처리를 분기합니다.
     */
    DUPLICATE_SUPPRESSED,
    /**
     * `HighContentionTerminalDisposition.IGNORED_FENCED`은 `ignored fenced` 업무 상황을 나타냅니다.
     * 성공·재시도·보상·수동 대사 중 하나로 후속 처리를 분기합니다.
     */
    IGNORED_FENCED,
}

/**
 * `HighContentionFailurePoint`은 실패·오류 분류 코드입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
enum class HighContentionFailurePoint {
    /**
     * `HighContentionFailurePoint.NONE`은 `none` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한
     * 뒤 사용합니다.
     */
    NONE,
    /**
     * `HighContentionFailurePoint.BEFORE_AUTHORITY`은 `before authority` 업무 상황을 나타냅니다. 현재
     * aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    BEFORE_AUTHORITY,
    /**
     * `HighContentionFailurePoint.AFTER_AUTHORITY`은 `after authority` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    AFTER_AUTHORITY,
    /**
     * `HighContentionFailurePoint.UNKNOWN`은 `unknown` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된
     * 전이를 확인한 뒤 사용합니다.
     */
    UNKNOWN,
}

/**
 * HighContentionSubmissionConservation 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionSubmissionConservation(
/**
 * scheduledCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val scheduledCount: Int,
/**
 * dispatchedCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val dispatchedCount: Int,
/**
 * completedCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val completedCount: Int,
/**
 * cancelledCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val cancelledCount: Int,
/**
 * timedOutCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val timedOutCount: Int,
/**
 * locallyRejectedCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val locallyRejectedCount: Int,
/**
 * terminalDispositionCounts은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val terminalDispositionCounts: Map<HighContentionTerminalDisposition, Int>,
) {
/**
 * validate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun validate(): HighContentionSubmissionConservation =
        apply {
            scheduledCount.requireZeroOrPositiveNumber("scheduledCount")
            dispatchedCount.requireZeroOrPositiveNumber("dispatchedCount")
            completedCount.requireZeroOrPositiveNumber("completedCount")
            cancelledCount.requireZeroOrPositiveNumber("cancelledCount")
            timedOutCount.requireZeroOrPositiveNumber("timedOutCount")
            locallyRejectedCount.requireZeroOrPositiveNumber("locallyRejectedCount")
            terminalDispositionCounts.keys.requireEquals(
                HighContentionTerminalDisposition.entries.toSet(),
                "terminalDispositionCounts keys",
            )
            terminalDispositionCounts.values.forEach {
                it.requireZeroOrPositiveNumber("terminalDispositionCount")
            }
            scheduledCount.requireEquals(
                Math.addExact(dispatchedCount, locallyRejectedCount),
                "submission conservation",
            )
            dispatchedCount.requireEquals(
                Math.addExact(
                    completedCount,
                    Math.addExact(cancelledCount, timedOutCount),
                ),
                "terminal disposition conservation",
            )
            completedCount.requireEquals(
                completedDispositions.sumOf(terminalDispositionCounts::getValue),
                "completed terminal dispositions",
            )
            cancelledCount.requireEquals(
                terminalDispositionCounts.getValue(HighContentionTerminalDisposition.CANCELLED),
                "cancelled terminal disposition",
            )
            timedOutCount.requireEquals(
                terminalDispositionCounts.getValue(HighContentionTerminalDisposition.TIMED_OUT),
                "timed-out terminal disposition",
            )
            locallyRejectedCount.requireEquals(
                terminalDispositionCounts.getValue(HighContentionTerminalDisposition.LOCALLY_REJECTED),
                "locally-rejected terminal disposition",
            )
            dispatchedCount.requireEquals(
                HighContentionTerminalDisposition.entries
                    .filterNot { it == HighContentionTerminalDisposition.LOCALLY_REJECTED }
                    .sumOf(terminalDispositionCounts::getValue),
                "dispatched terminal dispositions",
            )
        }

    private companion object {
/**
 * completedDispositions은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val completedDispositions = setOf(
            HighContentionTerminalDisposition.SUCCEEDED,
            HighContentionTerminalDisposition.FAILED_CLOSED,
            HighContentionTerminalDisposition.EXECUTION_FAILED,
            HighContentionTerminalDisposition.DUPLICATE_SUPPRESSED,
            HighContentionTerminalDisposition.IGNORED_FENCED,
        )
    }
}

/**
 * `HighContentionWinnerRule`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
enum class HighContentionWinnerRule {
    /**
     * `HighContentionWinnerRule.EXACTLY_ONE`은 `exactly one` 업무 상황을 나타냅니다. 현재 aggregate의 revision과
     * 허용된 전이를 확인한 뒤 사용합니다.
     */
    EXACTLY_ONE,
    /**
     * `HighContentionWinnerRule.AT_MOST_ONE`은 `at most one` 업무 상황을 나타냅니다. 현재 aggregate의 revision과
     * 허용된 전이를 확인한 뒤 사용합니다.
     */
    AT_MOST_ONE,
    /**
     * `HighContentionWinnerRule.ZERO`은 `zero` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤
     * 사용합니다.
     */
    ZERO,
}

/**
 * HighContentionIdentityAttemptExpectation 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionIdentityAttemptExpectation(
/**
 * attemptCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val attemptCount: Int,
/**
 * winnerRule은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val winnerRule: HighContentionWinnerRule,
)

/**
 * HighContentionAttemptEvidence 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionAttemptEvidence(
/**
 * stableOrdinal은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val stableOrdinal: Int,
/**
 * identityOrdinal은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val identityOrdinal: Int,
/**
 * attemptOrdinal은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val attemptOrdinal: Int,
/**
 * terminalDisposition은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val terminalDisposition: HighContentionTerminalDisposition,
/**
 * failurePoint은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val failurePoint: HighContentionFailurePoint,
/**
 * authorityWinnerCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val authorityWinnerCount: Int,
/**
 * effectWinnerCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val effectWinnerCount: Int,
/**
 * receiptWinnerCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val receiptWinnerCount: Int,
)

/**
 * HighContentionAttemptConservation 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionAttemptConservation(
/**
 * expectedAttemptsByIdentity은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val expectedAttemptsByIdentity: Map<Int, HighContentionIdentityAttemptExpectation>,
/**
 * attempts은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val attempts: List<HighContentionAttemptEvidence>,
) {
/**
 * validate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun validate(): HighContentionAttemptConservation =
        apply {
            expectedAttemptsByIdentity.requireNotEmpty("expectedAttemptsByIdentity")
            expectedAttemptsByIdentity.forEach { (identityOrdinal, expectation) ->
                identityOrdinal.requireZeroOrPositiveNumber("expected identityOrdinal")
                expectation.attemptCount.requirePositiveNumber("expected attemptCount")
            }
            attempts.forEach { attempt ->
                attempt.stableOrdinal.requireZeroOrPositiveNumber("stableOrdinal")
                attempt.identityOrdinal.requireZeroOrPositiveNumber("identityOrdinal")
                attempt.attemptOrdinal.requireZeroOrPositiveNumber("attemptOrdinal")
                attempt.authorityWinnerCount.requireInRange(0, 1, "authorityWinnerCount")
                attempt.effectWinnerCount.requireInRange(0, 1, "effectWinnerCount")
                attempt.receiptWinnerCount.requireInRange(0, 1, "receiptWinnerCount")
                if (attempt.terminalDisposition == HighContentionTerminalDisposition.EXECUTION_FAILED) {
                    if (attempt.failurePoint == HighContentionFailurePoint.NONE) {
                        throw IllegalArgumentException("EXECUTION_FAILED requires a failurePoint")
                    }
                } else {
                    attempt.failurePoint.requireEquals(
                        HighContentionFailurePoint.NONE,
                        "non-execution-failed failurePoint",
                    )
                }
            }

/**
 * expectedAttemptCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val expectedAttemptCount = expectedAttemptsByIdentity.values.fold(0) { total, expectation ->
                Math.addExact(total, expectation.attemptCount)
            }
            attempts.size.requireEquals(expectedAttemptCount, "attempt evidence count")
            attempts
                .map(HighContentionAttemptEvidence::stableOrdinal)
                .sorted()
                .requireEquals(attempts.indices.toList(), "attempt stable ordinals")

/**
 * attemptsByIdentity은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val attemptsByIdentity = attempts.groupBy(HighContentionAttemptEvidence::identityOrdinal)
            attemptsByIdentity.keys.requireEquals(
                expectedAttemptsByIdentity.keys,
                "attempt identity ordinals",
            )
            expectedAttemptsByIdentity.forEach { (identityOrdinal, expectation) ->
/**
 * identityAttempts은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val identityAttempts = attemptsByIdentity.getValue(identityOrdinal)
                identityAttempts
                    .map(HighContentionAttemptEvidence::attemptOrdinal)
                    .sorted()
                    .requireEquals(
                        (0 until expectation.attemptCount).toList(),
                        "attempt ordinals for identity $identityOrdinal",
                    )
                validateWinnerCount(
                    identityAttempts.sumOf(HighContentionAttemptEvidence::authorityWinnerCount),
                    expectation.winnerRule,
                    "authority winner count for identity $identityOrdinal",
                )
                validateWinnerCount(
                    identityAttempts.sumOf(HighContentionAttemptEvidence::effectWinnerCount),
                    expectation.winnerRule,
                    "effect winner count for identity $identityOrdinal",
                )
                validateWinnerCount(
                    identityAttempts.sumOf(HighContentionAttemptEvidence::receiptWinnerCount),
                    expectation.winnerRule,
                    "receipt winner count for identity $identityOrdinal",
                )
            }
        }

/**
 * validateWinnerCount은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun validateWinnerCount(
        count: Int,
        rule: HighContentionWinnerRule,
        name: String,
    ) {
        when (rule) {
            HighContentionWinnerRule.EXACTLY_ONE -> count.requireEquals(1, name)
            HighContentionWinnerRule.AT_MOST_ONE -> count.requireInRange(0, 1, name)
            HighContentionWinnerRule.ZERO -> count.requireEquals(0, name)
        }
    }
}

/**
 * HighContentionEffectiveConfiguration 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionEffectiveConfiguration(
/**
 * operationCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val operationCount: Int,
/**
 * concurrency은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val concurrency: Int,
/**
 * dispatcherBacklogCapacity은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val dispatcherBacklogCapacity: Int,
/**
 * warmupOperationCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val warmupOperationCount: Int,
) {
/**
 * validate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun validate(): HighContentionEffectiveConfiguration =
        apply {
            operationCount.requirePositiveNumber("operationCount")
            concurrency.requirePositiveNumber("concurrency")
            dispatcherBacklogCapacity.requireZeroOrPositiveNumber("dispatcherBacklogCapacity")
            warmupOperationCount.requireZeroOrPositiveNumber("warmupOperationCount")
        }
}

/**
 * HighContentionEnvironmentEvidence 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionEnvironmentEvidence(
/**
 * sourceCommit은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val sourceCommit: String,
/**
 * sourceDirty은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val sourceDirty: Boolean,
/**
 * sourceReproducible은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val sourceReproducible: Boolean,
/**
 * workflowRunAndAttempt은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val workflowRunAndAttempt: String,
) {
/**
 * validate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun validate(): HighContentionEnvironmentEvidence =
        apply {
            sourceCommit.requireNotBlank("sourceCommit")
            workflowRunAndAttempt.requireNotBlank("workflowRunAndAttempt")
            require(
                workflowRunAndAttempt == "local-0" ||
                    WORKFLOW_RUN_AND_ATTEMPT.matches(workflowRunAndAttempt),
            ) {
                "workflowRunAndAttempt must be local-0 or a hosted workflow run and attempt"
            }
            if (sourceDirty && sourceReproducible) {
                throw IllegalArgumentException("dirty source cannot be reproducible")
            }
        }

    private companion object {
/**
 * WORKFLOW_RUN_AND_ATTEMPT은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val WORKFLOW_RUN_AND_ATTEMPT = Regex("[0-9]+-[1-9][0-9]*")
    }
}

/**
 * HighContentionReportObservations 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionReportObservations(
/**
 * realizedAuthorityKeyCardinality은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val realizedAuthorityKeyCardinality: Int = 0,
/**
 * profileFields은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val profileFields: Map<String, Any> = emptyMap(),
) {
/**
 * validate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun validate(): HighContentionReportObservations =
        apply {
            realizedAuthorityKeyCardinality.requireZeroOrPositiveNumber("realizedAuthorityKeyCardinality")
            profileFields.forEach { (field, value) ->
                field.requireNotBlank("profile observation field")
                when (value) {
                    is Number -> require(value.toDouble().isFinite() && value.toDouble() >= 0.0) {
                        "numeric profile observation must be finite and non-negative"
                    }

                    is String -> value.requireNotBlank("profile observation value")
                    else -> throw IllegalArgumentException(
                        "profile observation value must be a number or string",
                    )
                }
            }
        }
}

/**
 * HighContentionScheduleEvidence 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionScheduleEvidence(
/**
 * expectedTokenCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val expectedTokenCount: Int,
/**
 * expectedScheduleSha256은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val expectedScheduleSha256: String,
/**
 * realizedTokenManifestSha256은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val realizedTokenManifestSha256: String,
) {
/**
 * validate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun validate(): HighContentionScheduleEvidence =
        apply {
            expectedTokenCount.requirePositiveNumber("expectedTokenCount")
            requireSha256(expectedScheduleSha256, "expectedScheduleSha256")
            requireSha256(realizedTokenManifestSha256, "realizedTokenManifestSha256")
        }
}

/**
 * HighContentionResourceOutcome 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionResourceOutcome(
/**
 * resourceKey은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val resourceKey: String,
/**
 * state은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val state: HighContentionResourceState,
)

/**
 * HighContentionWorkloadEvidence 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionWorkloadEvidence(
/**
 * effectiveConfiguration은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val effectiveConfiguration: HighContentionEffectiveConfiguration,
/**
 * schedule은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val schedule: HighContentionScheduleEvidence,
/**
 * submissionConservation은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val submissionConservation: HighContentionSubmissionConservation,
/**
 * attemptConservation은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val attemptConservation: HighContentionAttemptConservation,
) {
/**
 * validate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun validate(): HighContentionWorkloadEvidence =
        apply {
            effectiveConfiguration.validate()
            schedule.validate()
            submissionConservation.validate()
            attemptConservation.validate()
            effectiveConfiguration.operationCount.requireEquals(
                schedule.expectedTokenCount,
                "configured operationCount",
            )
            schedule.expectedTokenCount.requireEquals(
                submissionConservation.scheduledCount,
                "scheduled token count",
            )
            attemptConservation.attempts.size.requireEquals(
                schedule.expectedTokenCount,
                "realized attempt count",
            )
/**
 * realizedDispositionCounts은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val realizedDispositionCounts = HighContentionTerminalDisposition.entries.associateWith { disposition ->
                attemptConservation.attempts.count { it.terminalDisposition == disposition }
            }
            realizedDispositionCounts.requireEquals(
                submissionConservation.terminalDispositionCounts,
                "realized terminal disposition counts",
            )
        }
}

/**
 * HighContentionFailureInjectionEvidence 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionFailureInjectionEvidence(
/**
 * type은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val type: FailureKind,
/**
 * steps은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val steps: List<String>,
)

/**
 * `HighContentionInvariantStatus`은 상태 코드입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 */
enum class HighContentionInvariantStatus {
    /**
     * `HighContentionInvariantStatus.PASS`은 `pass` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를
     * 확인합니다.
     */
    PASS,
    /**
     * `HighContentionInvariantStatus.FAIL`은 `fail` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를
     * 확인합니다.
     */
    FAIL,
}

/**
 * HighContentionInvariantResult 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionInvariantResult(
/**
 * invariantId은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val invariantId: String,
/**
 * authority은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val authority: String,
/**
 * expectation은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val expectation: String,
/**
 * observation은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val observation: String,
/**
 * status은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val status: HighContentionInvariantStatus,
/**
 * evidenceReference은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val evidenceReference: String,
) {
/**
 * validate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun validate(): HighContentionInvariantResult =
        apply {
            HighContentionArtifactPaths.requireIdentifier(invariantId, "invariantId")
            authority.requireNotBlank("authority")
            expectation.requireNotBlank("expectation")
            observation.requireNotBlank("observation")
            HighContentionArtifactPaths.requireRelativeEvidence(evidenceReference)
        }
}

/**
 * `HighContentionTimeoutOrigin`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
enum class HighContentionTimeoutOrigin {
    /**
     * `HighContentionTimeoutOrigin.NONE`은 `none` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한
     * 뒤 사용합니다.
     */
    NONE,
    /**
     * `HighContentionTimeoutOrigin.OPERATION`은 `operation` 업무 상황을 나타냅니다. 현재 aggregate의 revision과
     * 허용된 전이를 확인한 뒤 사용합니다.
     */
    OPERATION,
    /**
     * `HighContentionTimeoutOrigin.INJECTION`은 `injection` 업무 상황을 나타냅니다. 현재 aggregate의 revision과
     * 허용된 전이를 확인한 뒤 사용합니다.
     */
    INJECTION,
    /**
     * `HighContentionTimeoutOrigin.FAILURE_DETECTION`은 `failure detection` 업무 상황을 나타냅니다. 현재
     * aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    FAILURE_DETECTION,
    /**
     * `HighContentionTimeoutOrigin.WORKLOAD_JOIN`은 `workload join` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    WORKLOAD_JOIN,
    /**
     * `HighContentionTimeoutOrigin.RECOVERY`은 `recovery` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된
     * 전이를 확인한 뒤 사용합니다.
     */
    RECOVERY,
    /**
     * `HighContentionTimeoutOrigin.CLEANUP`은 `cleanup` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된
     * 전이를 확인한 뒤 사용합니다.
     */
    CLEANUP,
    /**
     * `HighContentionTimeoutOrigin.PROFILE_EXECUTION`은 `profile execution` 업무 상황을 나타냅니다. 현재
     * aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    PROFILE_EXECUTION,
    /**
     * `HighContentionTimeoutOrigin.RUN_EXECUTION`은 `run execution` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    RUN_EXECUTION,
    /**
     * `HighContentionTimeoutOrigin.RUN_CLEANUP`은 `run cleanup` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    RUN_CLEANUP,
}

/**
 * HighContentionDeadlineEvidence 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionDeadlineEvidence(
/**
 * phase은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val phase: String,
/**
 * configuredBudgetNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val configuredBudgetNanos: Long,
/**
 * effectiveBudgetNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val effectiveBudgetNanos: Long,
/**
 * absoluteDeadlineNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val absoluteDeadlineNanos: Long,
/**
 * actualDurationNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val actualDurationNanos: Long,
/**
 * expired은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val expired: Boolean,
/**
 * timeoutOrigin은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val timeoutOrigin: HighContentionTimeoutOrigin,
) {
/**
 * validate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun validate(): HighContentionDeadlineEvidence =
        apply {
            HighContentionArtifactPaths.requireIdentifier(phase, "deadline phase")
            configuredBudgetNanos.requirePositiveNumber("configuredBudgetNanos")
            effectiveBudgetNanos.requireZeroOrPositiveNumber("effectiveBudgetNanos")
            effectiveBudgetNanos.requireInRange(0, configuredBudgetNanos, "effectiveBudgetNanos")
            absoluteDeadlineNanos.requireZeroOrPositiveNumber("absoluteDeadlineNanos")
            actualDurationNanos.requireZeroOrPositiveNumber("actualDurationNanos")
            if (expired) {
                if (timeoutOrigin == HighContentionTimeoutOrigin.NONE) {
                    throw IllegalArgumentException("expired deadline must identify timeoutOrigin")
                }
            } else {
                timeoutOrigin.requireEquals(
                    HighContentionTimeoutOrigin.NONE,
                    "non-expired deadline timeoutOrigin",
                )
            }
        }
}

/**
 * `HighContentionCleanupResult`은 명령 처리 결과입니다.
 * 외부 결과를 성공·재시도·보상·수동 대사로 분기할 때 사용하는 업무 계약입니다.
 */
enum class HighContentionCleanupResult {
    /**
     * `HighContentionCleanupResult.PASS`은 `pass` 업무 상황을 나타냅니다. 성공·재시도·보상·수동 대사 중 하나로 후속 처리를 분기합니다.
     */
    PASS,
    /**
     * `HighContentionCleanupResult.FAIL`은 `fail` 업무 상황을 나타냅니다. 성공·재시도·보상·수동 대사 중 하나로 후속 처리를 분기합니다.
     */
    FAIL,
}

/**
 * HighContentionCleanupSummary 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionCleanupSummary(
/**
 * result은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val result: HighContentionCleanupResult,
/**
 * resourceOutcomes은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val resourceOutcomes: List<HighContentionResourceOutcome> = emptyList(),
) {
/**
 * validate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun validate(): HighContentionCleanupSummary =
        apply {
            resourceOutcomes.forEach { outcome ->
                outcome.resourceKey.requireNotBlank("cleanup resourceKey")
                if (
                    outcome.state != HighContentionResourceState.CLOSED &&
                    outcome.state != HighContentionResourceState.CLOSE_FAILED
                ) {
                    throw IllegalArgumentException("cleanup resource state must be terminal")
                }
            }
            resourceOutcomes
                .map(HighContentionResourceOutcome::resourceKey)
                .distinct()
                .size
                .requireEquals(resourceOutcomes.size, "cleanup resource key cardinality")
/**
 * expectedResult은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val expectedResult = if (
                resourceOutcomes.any { it.state == HighContentionResourceState.CLOSE_FAILED }
            ) {
                HighContentionCleanupResult.FAIL
            } else {
                HighContentionCleanupResult.PASS
            }
            result.requireEquals(expectedResult, "cleanup result")
        }
}

/**
 * finalizeAfterSuccessfulCleanup은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
fun HighContentionTerminalReport.finalizeAfterSuccessfulCleanup(
    completedNanos: Long = System.nanoTime(),
    completedAt: Instant = Instant.now(),
): HighContentionTerminalReport {
/**
 * profileDeadline은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val profileDeadline = deadlines.singleOrNull { it.phase == "profile" } ?: deadlines.single()
/**
 * startedNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val startedNanos = profileDeadline.absoluteDeadlineNanos - profileDeadline.configuredBudgetNanos
/**
 * actualDurationNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val actualDurationNanos = (completedNanos - startedNanos).coerceAtLeast(0L)
/**
 * expired은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val expired = completedNanos > profileDeadline.absoluteDeadlineNanos
    return copy(
        endedAt = completedAt,
        deadlines = deadlines.map { deadline ->
            if (deadline == profileDeadline) {
                deadline.copy(
                    actualDurationNanos = actualDurationNanos,
                    expired = expired,
                    timeoutOrigin = if (expired) {
                        HighContentionTimeoutOrigin.PROFILE_EXECUTION
                    } else {
                        HighContentionTimeoutOrigin.NONE
                    },
                )
            } else {
                deadline
            }
        },
        result = if (expired) {
            HighContentionTerminalResult(
                terminalStatus = HighContentionTerminalStatus.ERROR,
                correctness = HighContentionCorrectness.PASS,
                errorCode = HighContentionErrorCode.WORKLOAD_TIMEOUT,
                failedPhase = profileDeadline.phase,
                failedInvariantIds = emptyList(),
                redactedErrorClass = null,
            )
        } else {
            result
        },
        cleanup = HighContentionCleanupSummary(HighContentionCleanupResult.PASS),
    )
}

/**
 * HighContentionTerminalReport 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionTerminalReport(
/**
 * reportSchemaVersion은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val reportSchemaVersion: Int,
/**
 * suiteSchemaVersion은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val suiteSchemaVersion: Int,
/**
 * profileSchemaVersion은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val profileSchemaVersion: Int,
/**
 * runId은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val runId: String,
/**
 * profileId은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val profileId: String,
/**
 * mode은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val mode: HighContentionMode,
/**
 * implementation은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val implementation: String,
/**
 * startedAt은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val startedAt: Instant,
/**
 * endedAt은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val endedAt: Instant,
/**
 * environment은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val environment: HighContentionEnvironmentEvidence,
/**
 * phaseDurationsNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val phaseDurationsNanos: Map<String, Long>,
/**
 * workload은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val workload: HighContentionWorkloadEvidence?,
/**
 * failureInjection은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val failureInjection: HighContentionFailureInjectionEvidence,
/**
 * invariantResults은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val invariantResults: List<HighContentionInvariantResult>,
/**
 * observations은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val observations: HighContentionReportObservations,
/**
 * deadlines은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val deadlines: List<HighContentionDeadlineEvidence>,
/**
 * observationScope은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val observationScope: String = "developer-or-ci-reference",
/**
 * crossImplementationComparable은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val crossImplementationComparable: Boolean = false,
/**
 * productionCapacityClaim은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val productionCapacityClaim: Boolean = false,
/**
 * result은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val result: HighContentionTerminalResult,
/**
 * cleanup은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val cleanup: HighContentionCleanupSummary,
/**
 * knownLimitations은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val knownLimitations: List<String>,
) {
/**
 * validate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun validate(): HighContentionTerminalReport =
        apply {
            reportSchemaVersion.requireEquals(1, "reportSchemaVersion")
            suiteSchemaVersion.requireEquals(1, "suiteSchemaVersion")
            profileSchemaVersion.requireEquals(1, "profileSchemaVersion")
            HighContentionArtifactPaths.requireIdentifier(runId, "runId")
            HighContentionArtifactPaths.requireIdentifier(profileId, "profileId")
            HighContentionArtifactPaths.requireIdentifier(implementation, "implementation")
            if (endedAt.isBefore(startedAt)) {
                throw IllegalArgumentException("endedAt must not precede startedAt")
            }
            environment.validate()
            phaseDurationsNanos.requireNotEmpty("phaseDurationsNanos")
            phaseDurationsNanos.forEach { (phase, duration) ->
                HighContentionArtifactPaths.requireIdentifier(phase, "phase")
                duration.requireZeroOrPositiveNumber("phase duration")
            }
            failureInjection.steps.forEach { it.requireNotBlank("failure injection step") }
            observations.validate()
            deadlines.requireNotEmpty("deadlines")
            deadlines.forEach(HighContentionDeadlineEvidence::validate)
            observationScope.requireEquals("developer-or-ci-reference", "observationScope")
            crossImplementationComparable.requireEquals(false, "crossImplementationComparable")
            productionCapacityClaim.requireEquals(false, "productionCapacityClaim")
            result.validate()
/**
 * evaluated은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val evaluated = result.correctness != HighContentionCorrectness.NOT_EVALUATED
            if (evaluated) {
                workload.requireNotNull("evaluated workload").validate()
                invariantResults.requireNotEmpty("evaluated invariantResults")
                invariantResults.forEach(HighContentionInvariantResult::validate)
            } else {
                workload.requireEquals(null, "not-evaluated workload")
                invariantResults.requireEquals(emptyList(), "not-evaluated invariantResults")
            }
            cleanup.validate()
            if (cleanup.result == HighContentionCleanupResult.FAIL) {
                result.terminalStatus.requireEquals(
                    HighContentionTerminalStatus.ERROR,
                    "cleanup failure result",
                )
                result.errorCode.requireEquals(
                    HighContentionErrorCode.CLEANUP_TIMEOUT,
                    "cleanup failure errorCode",
                )
            }
            if (result.errorCode == HighContentionErrorCode.CLEANUP_TIMEOUT) {
                cleanup.result.requireEquals(HighContentionCleanupResult.FAIL, "cleanup failure evidence")
            }
/**
 * executionFailureCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val executionFailureCount = workload
                ?.attemptConservation
                ?.attempts
                ?.count { it.terminalDisposition == HighContentionTerminalDisposition.EXECUTION_FAILED }
                ?: 0
            if (executionFailureCount > 0) {
                result.terminalStatus.requireEquals(
                    HighContentionTerminalStatus.ERROR,
                    "execution failure terminalStatus",
                )
                result.errorCode.requireEquals(
                    HighContentionErrorCode.EXECUTION_ERROR,
                    "execution failure errorCode",
                )
            }
            if (result.errorCode == HighContentionErrorCode.EXECUTION_ERROR) {
                executionFailureCount.requirePositiveNumber("execution failure evidence")
            }
/**
 * failedInvariantIds은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val failedInvariantIds = invariantResults
                .filter { it.status == HighContentionInvariantStatus.FAIL }
                .map(HighContentionInvariantResult::invariantId)
                .sorted()
            result.failedInvariantIds
                .sorted()
                .requireEquals(failedInvariantIds, "failed invariant evidence")
            if (evaluated) {
/**
 * derivedCorrectness은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val derivedCorrectness = if (failedInvariantIds.isNotEmpty()) {
                    HighContentionCorrectness.FAIL
                } else {
                    HighContentionCorrectness.PASS
                }
                result.correctness.requireEquals(derivedCorrectness, "derived correctness")
            }
            knownLimitations.requireNotEmpty("knownLimitations")
        }
}

/**
 * requireSha256은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun requireSha256(
    value: String,
    name: String,
): String {
/**
 * valid은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val valid = value.requireNotBlank(name)
    if (!SHA256_REGEX.matches(valid)) {
        throw IllegalArgumentException("$name must be a lower-case SHA-256 digest")
    }
    return valid
}

/**
 * SHA256_REGEX은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private val SHA256_REGEX = Regex("[0-9a-f]{64}")
