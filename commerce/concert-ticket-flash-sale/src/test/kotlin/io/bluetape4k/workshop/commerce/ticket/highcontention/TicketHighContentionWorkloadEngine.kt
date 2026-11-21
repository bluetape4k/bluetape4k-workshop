package io.bluetape4k.workshop.commerce.ticket.highcontention

import io.bluetape4k.concurrent.get
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.support.requireEndsWith
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.nanoseconds

/**
 * TicketWorkloadIdentity 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal data class TicketWorkloadIdentity(
/**
 * namespace은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val namespace: String,
/**
 * ordinal은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val ordinal: Int,
)

/**
 * `TicketWorkloadDisposition`은 후속 처리 판단입니다.
 * 외부 결과를 성공·재시도·보상·수동 대사로 분기할 때 사용하는 업무 계약입니다.
 */
internal enum class TicketWorkloadDisposition {
    /**
     * `TicketWorkloadDisposition.COMPLETED`은 업무 결과가 확정되어 정상 흐름이 종료된 값입니다.
     */
    COMPLETED,
    /**
     * `TicketWorkloadDisposition.CANCELLED`은 취소 요청과 필요한 보상 작업이 반영되어 더 이상 정상 진행하지 않는 값입니다.
     */
    CANCELLED,
    /**
     * `TicketWorkloadDisposition.TIMED_OUT`은 허용된 시간 예산 안에 결과를 얻지 못해 재시도·대사 판단이 필요한 값입니다.
     */
    TIMED_OUT,
    /**
     * `TicketWorkloadDisposition.LOCALLY_REJECTED`은 노드 로컬 admission/bulkhead에서 안전하게 거부한 값입니다.
     */
    LOCALLY_REJECTED,
}

/**
 * TicketWorkloadRecord 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal data class TicketWorkloadRecord(
/**
 * token은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val token: TicketScheduleToken,
/**
 * disposition은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val disposition: TicketWorkloadDisposition,
/**
 * missedDeadline은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val missedDeadline: Boolean,
/**
 * latencyNanos은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val latencyNanos: Long = 0L,
)

/**
 * TicketHighContentionWorkloadAdapter 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal interface TicketHighContentionWorkloadAdapter {
/**
 * warmUp은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun warmUp(identity: TicketWorkloadIdentity)

/**
 * snapshotBaseline은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun snapshotBaseline(): String

/**
 * execute은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun execute(
        token: TicketScheduleToken,
        identity: TicketWorkloadIdentity,
    ): TicketWorkloadDisposition
}

/**
 * `TicketFaultObserverTiming`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
internal enum class TicketFaultObserverTiming {
    /**
     * `TicketFaultObserverTiming.SCHEDULE_THRESHOLD`은 `schedule threshold` 업무 상황을 나타냅니다. 현재
     * aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    SCHEDULE_THRESHOLD,
    /**
     * `TicketFaultObserverTiming.WORKLOAD_COMPLETION`은 `workload completion` 업무 상황을 나타냅니다. 현재
     * aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    WORKLOAD_COMPLETION,
}

/**
 * TicketWorkloadResult 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal data class TicketWorkloadResult(
/**
 * baseline은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val baseline: String,
/**
 * expectedTokenCount은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val expectedTokenCount: Int,
/**
 * scheduledCount은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val scheduledCount: Int,
/**
 * dispatchedCount은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val dispatchedCount: Int,
/**
 * completedCount은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val completedCount: Int,
/**
 * cancelledCount은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val cancelledCount: Int,
/**
 * timedOutCount은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val timedOutCount: Int,
/**
 * locallyRejectedCount은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val locallyRejectedCount: Int,
/**
 * missedDeadlineCount은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val missedDeadlineCount: Int,
/**
 * expectedScheduleDigest은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val expectedScheduleDigest: String,
/**
 * realizedScheduleDigest은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val realizedScheduleDigest: String,
/**
 * realizedRecords은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val realizedRecords: List<TicketWorkloadRecord>,
/**
 * actualDurationNanos은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val actualDurationNanos: Long = 0L,
)

/**
 * TicketHighContentionWorkloadEngine 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketHighContentionWorkloadEngine(
/**
 * workloadJoinTimeout은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val workloadJoinTimeout: Duration,
) {
/**
 * run은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun run(
        schedule: List<TicketScheduleToken>,
        warmupOperationCount: Int,
        warmupNamespace: String,
        measuredNamespace: String,
        concurrency: Int,
        dispatcherBacklogCapacity: Int,
        maxScheduleDelayNanos: Long,
        adapter: TicketHighContentionWorkloadAdapter,
        faultObserverStartAfterScheduledCount: Int = schedule.size,
        faultObserverTiming: TicketFaultObserverTiming =
            TicketFaultObserverTiming.SCHEDULE_THRESHOLD,
        faultObserver: () -> Unit = {},
    ): TicketWorkloadResult {
/**
 * orderedSchedule은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val orderedSchedule = validateExpectedSchedule(schedule)
/**
 * validWarmupNamespace은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val validWarmupNamespace = warmupNamespace
            .requireNotBlank("warmupNamespace")
            .requireEndsWith(":", "warmupNamespace")
/**
 * validMeasuredNamespace은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val validMeasuredNamespace = measuredNamespace
            .requireNotBlank("measuredNamespace")
            .requireEndsWith(":", "measuredNamespace")
        require(
            !validWarmupNamespace.startsWith(validMeasuredNamespace) &&
                    !validMeasuredNamespace.startsWith(validWarmupNamespace),
        ) {
            "warm-up and measured namespaces must not overlap"
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
        require(
            faultObserverTiming != TicketFaultObserverTiming.WORKLOAD_COMPLETION ||
                    faultObserverStartAfterScheduledCount == orderedSchedule.size,
        ) {
            "workload-completion observer timing requires the end-of-schedule threshold"
        }
        require(!workloadJoinTimeout.isZero && !workloadJoinTimeout.isNegative) {
            "workloadJoinTimeout must be positive"
        }

        repeat(warmupOperationCount) { ordinal ->
            adapter.warmUp(TicketWorkloadIdentity(validWarmupNamespace, ordinal))
        }
/**
 * baseline은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val baseline = adapter.snapshotBaseline()
/**
 * totalPermits은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val totalPermits = Semaphore(Math.addExact(concurrency, dispatcherBacklogCapacity), true)
/**
 * executionPermits은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val executionPermits = Semaphore(concurrency, true)
/**
 * records은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val records = ConcurrentHashMap<Int, TicketWorkloadRecord>()
/**
 * futures은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val futures = mutableListOf<Future<*>>()
/**
 * startNanos은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val startNanos = System.nanoTime()

        VirtualThreads.executorService().use { workloadExecutor ->
            VirtualThreads.executorService().use { observerExecutor ->
/**
 * observeAfterWorkload은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val observeAfterWorkload = faultObserverTiming == TicketFaultObserverTiming.WORKLOAD_COMPLETION
/**
 * observerFuture은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                var observerFuture: Future<*>? = null
/**
 * startObserver은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
                fun startObserver(scheduledCount: Int) {
                    if (
                        !observeAfterWorkload &&
                        observerFuture == null &&
                        scheduledCount >= faultObserverStartAfterScheduledCount
                    ) {
                        observerFuture = observerExecutor.submit(faultObserver)
                    }
                }

                startObserver(0)
                orderedSchedule.forEachIndexed { index, token ->
                    awaitOffset(startNanos, token.offsetNanos)
                    if (!totalPermits.tryAcquire()) {
                        putExactlyOnce(
                            records = records,
                            record = TicketWorkloadRecord(
                                token,
                                TicketWorkloadDisposition.LOCALLY_REJECTED,
                                false
                            ),
                        )
                    } else {
                        futures += workloadExecutor.submit {
                            try {
                                executionPermits.acquire()
                                try {
/**
 * dispatchDelay은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                                    val dispatchDelay = elapsedSince(startNanos) - token.offsetNanos
/**
 * executionStartedNanos은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                                    val executionStartedNanos = System.nanoTime()
/**
 * disposition은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                                    val disposition = adapter.execute(
                                        token,
                                        TicketWorkloadIdentity(validMeasuredNamespace, token.identityOrdinal),
                                    )
                                    check(disposition != TicketWorkloadDisposition.LOCALLY_REJECTED) {
                                        "only the dispatcher records local rejection"
                                    }
                                    putExactlyOnce(
                                        records,
                                        TicketWorkloadRecord(
                                            token,
                                            disposition,
                                            dispatchDelay > maxScheduleDelayNanos,
                                            elapsedSince(executionStartedNanos),
                                        ),
                                    )
                                } finally {
                                    executionPermits.release()
                                }
                            } finally {
                                totalPermits.release()
                            }
                        }
                    }
                    startObserver(index + 1)
                }
                startObserver(orderedSchedule.size)
                awaitAll(
                    futures = futures,
                    observerFuture = observerFuture,
                    deferredObserver = if (observeAfterWorkload) {
                        { observerExecutor.submit(faultObserver) }
                    } else {
                        null
                    },
                )
            }
        }

/**
 * realized은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val realized = records.values.sortedBy { it.token.stableOrdinal }
        validateRealization(orderedSchedule, realized)

/**
 * dispatched은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val dispatched = realized.count { it.disposition != TicketWorkloadDisposition.LOCALLY_REJECTED }
/**
 * completed은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val completed = realized.count { it.disposition == TicketWorkloadDisposition.COMPLETED }
/**
 * cancelled은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val cancelled = realized.count { it.disposition == TicketWorkloadDisposition.CANCELLED }
/**
 * timedOut은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val timedOut = realized.count { it.disposition == TicketWorkloadDisposition.TIMED_OUT }
/**
 * rejected은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val rejected = realized.count { it.disposition == TicketWorkloadDisposition.LOCALLY_REJECTED }

        check(orderedSchedule.size == dispatched + rejected)
        check(dispatched == completed + cancelled + timedOut)

        return TicketWorkloadResult(
            baseline = baseline,
            expectedTokenCount = orderedSchedule.size,
            scheduledCount = orderedSchedule.size,
            dispatchedCount = dispatched,
            completedCount = completed,
            cancelledCount = cancelled,
            timedOutCount = timedOut,
            locallyRejectedCount = rejected,
            missedDeadlineCount = realized.count(TicketWorkloadRecord::missedDeadline),
            expectedScheduleDigest = TicketDeterministicSchedule.digest(orderedSchedule),
            realizedScheduleDigest = TicketDeterministicSchedule.digest(realized.map(TicketWorkloadRecord::token)),
            realizedRecords = realized,
            actualDurationNanos = elapsedSince(startNanos),
        )
    }

/**
 * awaitAll은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun awaitAll(
        futures: List<Future<*>>,
        observerFuture: Future<*>?,
        deferredObserver: (() -> Future<*>)?,
    ) {
/**
 * started은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val started = System.nanoTime()
/**
 * timeoutNanos은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val timeoutNanos = workloadJoinTimeout.toNanos()
/**
 * activeObserver은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var activeObserver = observerFuture

        try {
            activeObserver?.get(remainingNanos(started, timeoutNanos).nanoseconds)
            futures.forEach { it.get(remainingNanos(started, timeoutNanos).nanoseconds) }
            activeObserver = deferredObserver?.invoke()
            activeObserver?.get(remainingNanos(started, timeoutNanos).nanoseconds)
        } catch (error: TimeoutException) {
            futures.forEach { it.cancel(true) }
            activeObserver?.cancel(true)
            throw IllegalStateException("Ticket workload did not join before its deadline", error)
        } catch (error: ExecutionException) {
            futures.forEach { it.cancel(true) }
            activeObserver?.cancel(true)
            throw IllegalStateException("Ticket workload execution failed", error.cause ?: error)
        }
    }

/**
 * remainingNanos은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun remainingNanos(started: Long, timeoutNanos: Long): Long =
        (timeoutNanos - elapsedSince(started)).also {
            if (it <= 0L) {
                throw TimeoutException("Ticket workload deadline expired")
            }
        }

    internal companion object {
/**
 * validateRealization은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun validateRealization(
            expected: List<TicketScheduleToken>,
            realized: List<TicketWorkloadRecord>,
        ) {
/**
 * expectedByOrdinal은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val expectedByOrdinal = expected.associateBy(TicketScheduleToken::stableOrdinal)
            check(expectedByOrdinal.size == expected.size) { "expected schedule contains duplicate ordinals" }

/**
 * realizedByOrdinal은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val realizedByOrdinal = realized.groupBy { it.token.stableOrdinal }
            check(realizedByOrdinal.values.none { it.size != 1 }) { "realized schedule contains duplicate ordinals" }
            check(realizedByOrdinal.keys == expectedByOrdinal.keys) {
                "realized schedule has missing or unknown ordinals"
            }

            realizedByOrdinal.forEach { (ordinal, records) ->
                check(records.single().token == expectedByOrdinal.getValue(ordinal)) {
                    "realized token $ordinal differs from expected"
                }
            }
        }

/**
 * validateExpectedSchedule은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun validateExpectedSchedule(schedule: List<TicketScheduleToken>): List<TicketScheduleToken> {
            schedule.requireNotEmpty("schedule")
            val ordered = schedule.sortedWith(
                compareBy<TicketScheduleToken> { it.offsetNanos }.thenBy { it.stableOrdinal },
            )
            require(ordered.map { it.stableOrdinal }.sorted() == ordered.indices.toList()) {
                "stable ordinals must be exactly 0 until operationCount"
            }
            require(ordered.none { it.offsetNanos < 0L }) { "schedule offsets must be non-negative" }
            return ordered
        }

/**
 * putExactlyOnce은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun putExactlyOnce(
            records: ConcurrentHashMap<Int, TicketWorkloadRecord>,
            record: TicketWorkloadRecord,
        ) {
            check(records.putIfAbsent(record.token.stableOrdinal, record) == null) {
                "stable ordinal ${record.token.stableOrdinal} was realized more than once"
            }
        }

/**
 * awaitOffset은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
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
                    throw InterruptedException("Ticket scheduler interrupted")
                }
            }
        }

/**
 * elapsedSince은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun elapsedSince(startNanos: Long): Long = System.nanoTime() - startNanos
    }
}
