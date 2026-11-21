package io.bluetape4k.workshop.operations.jobconsole.worker

import io.bluetape4k.javatimes.millis
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.operations.jobconsole.domain.JobSignal
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.persistence.ClaimedJob
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import java.time.Clock
import java.time.Duration

/**
 * JobWorkerEngine은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
class JobWorkerEngine(
    /**
     * `repository`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: JobRepository,
    /**
     * `workload`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val workload: DeterministicJobWorkload,
    /**
     * `clock`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock = Clock.systemUTC(),
) {

    companion object: KLogging()

    /**
     * runOnce은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun runOnce(
        leaseDuration: Duration = Duration.ofSeconds(30),
        tenantLimit: Int = 100,
    ): Boolean {
        for (tenantId in repository.runnableTenantIds(tenantLimit)) {
            /**
             * `claimed`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val claimed = repository.reclaimExpired(tenantId, leaseDuration)
                ?: repository.claimNext(tenantId, leaseDuration)

            if (claimed != null) {
                run(claimed)
                return true
            }
        }
        return false
    }

    /**
     * run은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param claimed 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun run(claimed: ClaimedJob) {
        var current = claimed
        val startedAt = clock.instant()

        try {
            for (unit in (claimed.completedChunk + 1)..claimed.workUnits.toLong()) {
                workload.execute(current, unit)
                val progress = ((unit * 100) / claimed.workUnits).toInt()
                val checkpoint = repository.checkpoint(current.lease, unit, progress)
                if (checkpoint.state == JobState.CANCELLED) return

                current = current.copy(lease = checkpoint.lease, completedChunk = checkpoint.completedChunk)
            }
            repository.complete(current.lease, JobSignal.SUCCESS)
            val completedAt = clock.instant()
            val elapsed = Duration.between(startedAt, completedAt).coerceAtLeast(1.millis())

            repository.recordDuration(current.jobType, elapsed, completedAt)

        } catch (failure: DeterministicWorkloadFailure) {
            repository.complete(
                current.lease,
                if (failure.retryable) JobSignal.RETRYABLE_FAILURE else JobSignal.NON_RETRYABLE_FAILURE,
            )
        }
    }
}
