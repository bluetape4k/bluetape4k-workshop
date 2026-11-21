package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.support.requireEndsWith
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.LockSupport

/**
 * WorkloadIdentity 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class WorkloadIdentity(
/**
 * namespace은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val namespace: String,
/**
 * ordinal은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val ordinal: Int,
)

/**
 * `WorkloadTerminalDisposition`은 후속 처리 판단입니다.
 * 외부 결과를 성공·재시도·보상·수동 대사로 분기할 때 사용하는 업무 계약입니다.
 */
enum class WorkloadTerminalDisposition {
    /**
     * `WorkloadTerminalDisposition.COMPLETED`은 업무 결과가 확정되어 정상 흐름이 종료된 값입니다.
     */
    COMPLETED,
    /**
     * `WorkloadTerminalDisposition.CANCELLED`은 취소 요청과 필요한 보상 작업이 반영되어 더 이상 정상 진행하지 않는 값입니다.
     */
    CANCELLED,
    /**
     * `WorkloadTerminalDisposition.TIMED_OUT`은 `timed out` 업무 상황을 나타냅니다. 성공·재시도·보상·수동 대사 중 하나로 후속
     * 처리를 분기합니다.
     */
    TIMED_OUT,
    /**
     * `WorkloadTerminalDisposition.LOCALLY_REJECTED`은 `locally rejected` 업무 상황을 나타냅니다.
     * 성공·재시도·보상·수동 대사 중 하나로 후속 처리를 분기합니다.
     */
    LOCALLY_REJECTED,
}

/**
 * WorkloadRealizedRecord 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class WorkloadRealizedRecord(
/**
 * token은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val token: ScheduleToken,
/**
 * disposition은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val disposition: WorkloadTerminalDisposition,
/**
 * missedDeadline은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val missedDeadline: Boolean,
/**
 * latencyNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val latencyNanos: Long = 0L,
)

/**
 * HighContentionWorkloadAdapter 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
interface HighContentionWorkloadAdapter {
/**
 * warmUp은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun warmUp(identity: WorkloadIdentity)

/**
 * snapshotBaseline은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun snapshotBaseline(): String

/**
 * execute은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun execute(
        token: ScheduleToken,
        identity: WorkloadIdentity,
    ): WorkloadTerminalDisposition
}

/**
 * HighContentionWorkloadResult 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionWorkloadResult(
/**
 * baseline은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val baseline: String,
/**
 * expectedTokenCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val expectedTokenCount: Int,
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
 * missedDeadlineCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val missedDeadlineCount: Int,
/**
 * expectedScheduleDigest은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val expectedScheduleDigest: String,
/**
 * realizedScheduleDigest은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val realizedScheduleDigest: String,
/**
 * realizedRecords은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val realizedRecords: List<WorkloadRealizedRecord>,
/**
 * actualDurationNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val actualDurationNanos: Long = 0L,
)

/**
 * HighContentionWorkloadEngine 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class HighContentionWorkloadEngine(
/**
 * workloadJoinTimeout은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val workloadJoinTimeout: Duration = Duration.ofSeconds(30),
) {

/**
 * run은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun run(
        schedule: List<ScheduleToken>,
        warmupOperationCount: Int,
        warmupNamespace: String,
        measuredNamespace: String,
        concurrency: Int,
        dispatcherBacklogCapacity: Int,
        maxScheduleDelayNanos: Long,
        adapter: HighContentionWorkloadAdapter,
        faultObserverStartAfterScheduledCount: Int = 0,
        faultObserver: () -> Unit = {},
    ): HighContentionWorkloadResult {
/**
 * orderedSchedule은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val orderedSchedule = validateExpectedSchedule(schedule)
/**
 * validWarmupNamespace은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val validWarmupNamespace = warmupNamespace
            .requireNotBlank("warmupNamespace")
            .requireEndsWith(":", "warmupNamespace")
/**
 * validMeasuredNamespace은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val validMeasuredNamespace = measuredNamespace
            .requireNotBlank("measuredNamespace")
            .requireEndsWith(":", "measuredNamespace")
        if (
            validWarmupNamespace.startsWith(validMeasuredNamespace) ||
            validMeasuredNamespace.startsWith(validWarmupNamespace)
        ) {
            throw IllegalArgumentException("warm-up and measured namespaces must not overlap")
        }
        warmupOperationCount.requireZeroOrPositiveNumber("warmupOperationCount")
        concurrency.requirePositiveNumber("concurrency")
        dispatcherBacklogCapacity.requireZeroOrPositiveNumber("dispatcherBacklogCapacity")
        maxScheduleDelayNanos.requireZeroOrPositiveNumber("maxScheduleDelayNanos")
        faultObserverStartAfterScheduledCount.requireInRange(
            0,
            orderedSchedule.size,
            "faultObserverStartAfterScheduledCount",
        )
        if (workloadJoinTimeout.isZero || workloadJoinTimeout.isNegative) {
            throw IllegalArgumentException("workloadJoinTimeout must be positive")
        }

        repeat(warmupOperationCount) { ordinal ->
            adapter.warmUp(WorkloadIdentity(validWarmupNamespace, ordinal))
        }
/**
 * baseline은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val baseline = adapter.snapshotBaseline()
/**
 * totalInFlightPermits은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val totalInFlightPermits = Semaphore(
            Math.addExact(concurrency, dispatcherBacklogCapacity),
            true,
        )
/**
 * executionPermits은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val executionPermits = Semaphore(concurrency, true)
/**
 * records은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val records = ConcurrentHashMap<Int, WorkloadRealizedRecord>()
/**
 * workloadFutures은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val workloadFutures = mutableListOf<Future<*>>()
/**
 * startNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val startNanos = System.nanoTime()

        VirtualThreads.executorService().use { workloadExecutor ->
            VirtualThreads.executorService().use { observerExecutor ->
/**
 * observerFuture은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                var observerFuture: Future<*>? = null

/**
 * startFaultObserverIfRequired은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
                fun startFaultObserverIfRequired(scheduledCount: Int) {
                    if (
                        observerFuture == null &&
                        scheduledCount >= faultObserverStartAfterScheduledCount
                    ) {
                        observerFuture = observerExecutor.submit(faultObserver)
                    }
                }

                startFaultObserverIfRequired(0)
                orderedSchedule.forEachIndexed { index, token ->
                    awaitOffset(startNanos, token.offsetNanos)
                    if (!totalInFlightPermits.tryAcquire()) {
                        putExactlyOnce(
                            records,
                            WorkloadRealizedRecord(
                                token = token,
                                disposition = WorkloadTerminalDisposition.LOCALLY_REJECTED,
                                missedDeadline = false,
                            ),
                        )
                    } else {
                        workloadFutures += workloadExecutor.submit {
                            try {
                                executionPermits.acquire()
                                try {
/**
 * dispatchDelay은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                                    val dispatchDelay = elapsedSince(startNanos) - token.offsetNanos
/**
 * executionStartedNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                                    val executionStartedNanos = System.nanoTime()
/**
 * disposition은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                                    val disposition = adapter.execute(
                                        token,
                                        WorkloadIdentity(validMeasuredNamespace, token.identityOrdinal),
                                    )
                                    if (disposition == WorkloadTerminalDisposition.LOCALLY_REJECTED) {
                                        throw IllegalStateException("only the dispatcher can record LOCALLY_REJECTED")
                                    }
                                    putExactlyOnce(
                                        records,
                                        WorkloadRealizedRecord(
                                            token = token,
                                            disposition = disposition,
                                            missedDeadline = dispatchDelay > maxScheduleDelayNanos,
                                            latencyNanos = elapsedSince(executionStartedNanos),
                                        ),
                                    )
                                } finally {
                                    executionPermits.release()
                                }
                            } finally {
                                totalInFlightPermits.release()
                            }
                        }
                    }
                    startFaultObserverIfRequired(index + 1)
                }
                startFaultObserverIfRequired(orderedSchedule.size)
                awaitAll(workloadFutures, observerFuture)
            }
        }

/**
 * realized은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val realized = records.values.sortedBy { it.token.stableOrdinal }
        validateRealization(orderedSchedule, realized)
/**
 * dispatched은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val dispatched = realized.count { it.disposition != WorkloadTerminalDisposition.LOCALLY_REJECTED }
/**
 * completed은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val completed = realized.count { it.disposition == WorkloadTerminalDisposition.COMPLETED }
/**
 * cancelled은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val cancelled = realized.count { it.disposition == WorkloadTerminalDisposition.CANCELLED }
/**
 * timedOut은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val timedOut = realized.count { it.disposition == WorkloadTerminalDisposition.TIMED_OUT }
/**
 * locallyRejected은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val locallyRejected = realized.count { it.disposition == WorkloadTerminalDisposition.LOCALLY_REJECTED }
/**
 * missedDeadline은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val missedDeadline = realized.count(WorkloadRealizedRecord::missedDeadline)

        check(orderedSchedule.size == dispatched + locallyRejected) {
            "scheduledCount must equal dispatchedCount + locallyRejectedCount"
        }
        check(dispatched == completed + cancelled + timedOut) {
            "dispatchedCount must equal completedCount + cancelledCount + timedOutCount"
        }

        return HighContentionWorkloadResult(
            baseline = baseline,
            expectedTokenCount = orderedSchedule.size,
            scheduledCount = orderedSchedule.size,
            dispatchedCount = dispatched,
            completedCount = completed,
            cancelledCount = cancelled,
            timedOutCount = timedOut,
            locallyRejectedCount = locallyRejected,
            missedDeadlineCount = missedDeadline,
            expectedScheduleDigest = DeterministicSchedule.digest(orderedSchedule),
            realizedScheduleDigest = DeterministicSchedule.digest(realized.map(WorkloadRealizedRecord::token)),
            realizedRecords = realized,
            actualDurationNanos = elapsedSince(startNanos),
        )
    }

/**
 * awaitAll은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun awaitAll(
        workloadFutures: List<Future<*>>,
        observerFuture: Future<*>?,
    ) {
/**
 * joinStartedNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val joinStartedNanos = System.nanoTime()
/**
 * timeoutNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val timeoutNanos = workloadJoinTimeout.toNanos()
        try {
            observerFuture?.get(remainingNanos(joinStartedNanos, timeoutNanos), TimeUnit.NANOSECONDS)
            workloadFutures.forEach { future ->
                future.get(remainingNanos(joinStartedNanos, timeoutNanos), TimeUnit.NANOSECONDS)
            }
        } catch (error: TimeoutException) {
            workloadFutures.forEach { it.cancel(true) }
            observerFuture?.cancel(true)
            throw IllegalStateException("high-contention workload did not join before its deadline", error)
        } catch (error: ExecutionException) {
            workloadFutures.forEach { it.cancel(true) }
            observerFuture?.cancel(true)
            throw IllegalStateException("high-contention workload execution failed", error.cause ?: error)
        }
    }

/**
 * remainingNanos은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun remainingNanos(
        joinStartedNanos: Long,
        timeoutNanos: Long,
    ): Long {
/**
 * remaining은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val remaining = timeoutNanos - elapsedSince(joinStartedNanos)
        if (remaining <= 0L) {
            throw TimeoutException("high-contention workload deadline expired")
        }
        return remaining
    }

    companion object {
/**
 * validateRealization은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun validateRealization(
            expectedSchedule: List<ScheduleToken>,
            realizedRecords: List<WorkloadRealizedRecord>,
        ) {
/**
 * expectedByOrdinal은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val expectedByOrdinal = expectedSchedule.associateBy(ScheduleToken::stableOrdinal)
            if (expectedByOrdinal.size != expectedSchedule.size) {
                throw IllegalStateException("expected schedule contains duplicate stable ordinals")
            }
/**
 * realizedGroups은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val realizedGroups = realizedRecords.groupBy { it.token.stableOrdinal }
            if (realizedGroups.values.any { it.size != 1 }) {
                throw IllegalStateException("realized schedule contains duplicate stable ordinals")
            }
            if (realizedGroups.keys != expectedByOrdinal.keys) {
                throw IllegalStateException("realized schedule has missing or unknown stable ordinals")
            }
            realizedGroups.forEach { (ordinal, records) ->
                if (records.single().token != expectedByOrdinal.getValue(ordinal)) {
                    throw IllegalStateException("realized token $ordinal differs from the expected canonical token")
                }
            }
        }

/**
 * validateExpectedSchedule은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun validateExpectedSchedule(schedule: List<ScheduleToken>): List<ScheduleToken> {
            if (schedule.isEmpty()) {
                throw IllegalArgumentException("schedule must not be empty")
            }
            val ordered = schedule.sortedWith(
                compareBy<ScheduleToken> { it.offsetNanos }
                    .thenBy { it.stableOrdinal },
            )
            val stableOrdinals = ordered.map(ScheduleToken::stableOrdinal)
            if (stableOrdinals.sorted() != (ordered.indices).toList()) {
                throw IllegalArgumentException("schedule stable ordinals must be exactly 0 until operationCount")
            }
            if (ordered.any { it.offsetNanos < 0L }) {
                throw IllegalArgumentException("schedule offsets must be zero or positive")
            }
            return ordered
        }

/**
 * putExactlyOnce은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun putExactlyOnce(
            records: ConcurrentHashMap<Int, WorkloadRealizedRecord>,
            record: WorkloadRealizedRecord,
        ) {
            check(records.putIfAbsent(record.token.stableOrdinal, record) == null) {
                "stable ordinal ${record.token.stableOrdinal} was realized more than once"
            }
        }

/**
 * awaitOffset은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun awaitOffset(startNanos: Long, offsetNanos: Long) {
            while (true) {
                val remaining = offsetNanos - elapsedSince(startNanos)
                if (remaining <= 0L) {
                    return
                }
                LockSupport.parkNanos(remaining)
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("scheduler interrupted while awaiting an open-loop offset")
                }
            }
        }

/**
 * elapsedSince은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun elapsedSince(startNanos: Long): Long =
            System.nanoTime() - startNanos
    }
}
