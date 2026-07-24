package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.concurrent.virtualthread.VirtualThreads
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

data class WorkloadIdentity(
    val namespace: String,
    val ordinal: Int,
)

enum class WorkloadTerminalDisposition {
    COMPLETED,
    CANCELLED,
    TIMED_OUT,
    LOCALLY_REJECTED,
}

data class WorkloadRealizedRecord(
    val token: ScheduleToken,
    val disposition: WorkloadTerminalDisposition,
    val missedDeadline: Boolean,
)

interface HighContentionWorkloadAdapter {
    fun warmUp(identity: WorkloadIdentity)

    fun snapshotBaseline(): String

    fun execute(
        token: ScheduleToken,
        identity: WorkloadIdentity,
    ): WorkloadTerminalDisposition
}

data class HighContentionWorkloadResult(
    val baseline: String,
    val expectedTokenCount: Int,
    val scheduledCount: Int,
    val dispatchedCount: Int,
    val completedCount: Int,
    val cancelledCount: Int,
    val timedOutCount: Int,
    val locallyRejectedCount: Int,
    val missedDeadlineCount: Int,
    val expectedScheduleDigest: String,
    val realizedScheduleDigest: String,
    val realizedRecords: List<WorkloadRealizedRecord>,
)

class HighContentionWorkloadEngine(
    private val workloadJoinTimeout: Duration = Duration.ofSeconds(30),
) {

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
        val orderedSchedule = validateExpectedSchedule(schedule)
        val validWarmupNamespace = warmupNamespace
            .requireNotBlank("warmupNamespace")
            .requireEndsWith(":", "warmupNamespace")
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
        val baseline = adapter.snapshotBaseline()
        val totalInFlightPermits = Semaphore(
            Math.addExact(concurrency, dispatcherBacklogCapacity),
            true,
        )
        val executionPermits = Semaphore(concurrency, true)
        val records = ConcurrentHashMap<Int, WorkloadRealizedRecord>()
        val workloadFutures = mutableListOf<Future<*>>()
        val startNanos = System.nanoTime()

        VirtualThreads.executorService().use { workloadExecutor ->
            VirtualThreads.executorService().use { observerExecutor ->
                var observerFuture: Future<*>? = null

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
                                    val dispatchDelay = elapsedSince(startNanos) - token.offsetNanos
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

        val realized = records.values.sortedBy { it.token.stableOrdinal }
        validateRealization(orderedSchedule, realized)
        val dispatched = realized.count { it.disposition != WorkloadTerminalDisposition.LOCALLY_REJECTED }
        val completed = realized.count { it.disposition == WorkloadTerminalDisposition.COMPLETED }
        val cancelled = realized.count { it.disposition == WorkloadTerminalDisposition.CANCELLED }
        val timedOut = realized.count { it.disposition == WorkloadTerminalDisposition.TIMED_OUT }
        val locallyRejected = realized.count { it.disposition == WorkloadTerminalDisposition.LOCALLY_REJECTED }
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
        )
    }

    private fun awaitAll(
        workloadFutures: List<Future<*>>,
        observerFuture: Future<*>?,
    ) {
        val joinStartedNanos = System.nanoTime()
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

    private fun remainingNanos(
        joinStartedNanos: Long,
        timeoutNanos: Long,
    ): Long {
        val remaining = timeoutNanos - elapsedSince(joinStartedNanos)
        if (remaining <= 0L) {
            throw TimeoutException("high-contention workload deadline expired")
        }
        return remaining
    }

    companion object {
        fun validateRealization(
            expectedSchedule: List<ScheduleToken>,
            realizedRecords: List<WorkloadRealizedRecord>,
        ) {
            val expectedByOrdinal = expectedSchedule.associateBy(ScheduleToken::stableOrdinal)
            if (expectedByOrdinal.size != expectedSchedule.size) {
                throw IllegalStateException("expected schedule contains duplicate stable ordinals")
            }
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

        private fun putExactlyOnce(
            records: ConcurrentHashMap<Int, WorkloadRealizedRecord>,
            record: WorkloadRealizedRecord,
        ) {
            check(records.putIfAbsent(record.token.stableOrdinal, record) == null) {
                "stable ordinal ${record.token.stableOrdinal} was realized more than once"
            }
        }

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

        private fun elapsedSince(startNanos: Long): Long =
            System.nanoTime() - startNanos
    }
}
