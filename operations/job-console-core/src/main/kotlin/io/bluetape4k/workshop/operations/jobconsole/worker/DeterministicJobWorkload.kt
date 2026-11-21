package io.bluetape4k.workshop.operations.jobconsole.worker

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.persistence.ClaimedJob

/**
 * 외부 provider 없이 retryable/non-retryable 장애를 재현하는 fixture 예외입니다.
 * @property retryable 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class DeterministicWorkloadFailure(
    /**
     * `retryable`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val retryable: Boolean,
): RuntimeException(if (retryable) "retryable deterministic failure" else "non-retryable deterministic failure")

/**
 * `FailureMode`를 실제 work unit 실행 결과로 바꾸는 결정론적 workload입니다.
 *
 * 첫 work unit에서만 실패를 주입하므로 테스트는 wall-clock sleep 없이 retry,
 * dead-letter, checkpoint 경계를 검증할 수 있습니다.
 * 예: `FailureMode.RETRY_ONCE`는 attempt 1에서 예외를 내고 attempt 2에서는
 * 모든 unit을 통과합니다.
 */
class DeterministicJobWorkload {

    companion object: KLogging()

    /** 지정된 work unit을 실행하고 fixture failure mode를 적용합니다. */
    /** 지정된 work unit을 실행하고 fixture failure mode를 적용합니다. */
    fun execute(job: ClaimedJob, unit: Long) {
        unit.requireInRange(1, job.workUnits.toLong()) { "work unit is outside the declared job range" }

        if (unit != 1L) return

        when (job.failureMode) {
            FailureMode.NONE -> Unit
            FailureMode.RETRY_ONCE -> if (job.lease.attempt == 1) throw DeterministicWorkloadFailure(retryable = true)
            FailureMode.ALWAYS_RETRYABLE -> throw DeterministicWorkloadFailure(retryable = true)
            FailureMode.NON_RETRYABLE -> throw DeterministicWorkloadFailure(retryable = false)
        }
    }
}
