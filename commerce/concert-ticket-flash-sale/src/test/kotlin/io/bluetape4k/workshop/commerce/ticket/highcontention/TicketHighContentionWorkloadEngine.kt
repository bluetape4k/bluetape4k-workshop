package io.bluetape4k.workshop.commerce.ticket.highcontention

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

internal data class TicketWorkloadIdentity(
    val namespace: String,
    val ordinal: Int,
)

internal enum class TicketWorkloadDisposition {
    COMPLETED,
    CANCELLED,
    TIMED_OUT,
    LOCALLY_REJECTED,
}

internal data class TicketWorkloadRecord(
    val token: TicketScheduleToken,
    val disposition: TicketWorkloadDisposition,
    val missedDeadline: Boolean,
    val latencyNanos: Long = 0L,
)

internal interface TicketHighContentionWorkloadAdapter {
    fun warmUp(identity: TicketWorkloadIdentity)

    fun snapshotBaseline(): String

    fun execute(
        token: TicketScheduleToken,
        identity: TicketWorkloadIdentity,
    ): TicketWorkloadDisposition
}

internal enum class TicketFaultObserverTiming {
    SCHEDULE_THRESHOLD,
    WORKLOAD_COMPLETION,
}

internal data class TicketWorkloadResult(
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
    val realizedRecords: List<TicketWorkloadRecord>,
    val actualDurationNanos: Long = 0L,
)

internal class TicketHighContentionWorkloadEngine(
    private val workloadJoinTimeout: Duration,
) {
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
        val orderedSchedule = validateExpectedSchedule(schedule)
        val validWarmupNamespace = warmupNamespace
            .requireNotBlank("warmupNamespace")
            .requireEndsWith(":", "warmupNamespace")
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
        val baseline = adapter.snapshotBaseline()
        val totalPermits = Semaphore(Math.addExact(concurrency, dispatcherBacklogCapacity), true)
        val executionPermits = Semaphore(concurrency, true)
        val records = ConcurrentHashMap<Int, TicketWorkloadRecord>()
        val futures = mutableListOf<Future<*>>()
        val startNanos = System.nanoTime()

        VirtualThreads.executorService().use { workloadExecutor ->
            VirtualThreads.executorService().use { observerExecutor ->
                val observeAfterWorkload =
                    faultObserverTiming == TicketFaultObserverTiming.WORKLOAD_COMPLETION
                var observerFuture: Future<*>? = null
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
                            records,
                            TicketWorkloadRecord(token, TicketWorkloadDisposition.LOCALLY_REJECTED, false),
                        )
                    } else {
                        futures += workloadExecutor.submit {
                            try {
                                executionPermits.acquire()
                                try {
                                    val dispatchDelay = elapsedSince(startNanos) - token.offsetNanos
                                    val executionStartedNanos = System.nanoTime()
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

        val realized = records.values.sortedBy { it.token.stableOrdinal }
        validateRealization(orderedSchedule, realized)
        val dispatched = realized.count { it.disposition != TicketWorkloadDisposition.LOCALLY_REJECTED }
        val completed = realized.count { it.disposition == TicketWorkloadDisposition.COMPLETED }
        val cancelled = realized.count { it.disposition == TicketWorkloadDisposition.CANCELLED }
        val timedOut = realized.count { it.disposition == TicketWorkloadDisposition.TIMED_OUT }
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

    private fun awaitAll(
        futures: List<Future<*>>,
        observerFuture: Future<*>?,
        deferredObserver: (() -> Future<*>)?,
    ) {
        val started = System.nanoTime()
        val timeoutNanos = workloadJoinTimeout.toNanos()
        var activeObserver = observerFuture
        try {
            activeObserver?.get(remainingNanos(started, timeoutNanos), TimeUnit.NANOSECONDS)
            futures.forEach { it.get(remainingNanos(started, timeoutNanos), TimeUnit.NANOSECONDS) }
            activeObserver = deferredObserver?.invoke()
            activeObserver?.get(remainingNanos(started, timeoutNanos), TimeUnit.NANOSECONDS)
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

    private fun remainingNanos(started: Long, timeoutNanos: Long): Long =
        (timeoutNanos - elapsedSince(started)).also {
            if (it <= 0L) {
                throw TimeoutException("Ticket workload deadline expired")
            }
        }

    internal companion object {
        fun validateRealization(
            expected: List<TicketScheduleToken>,
            realized: List<TicketWorkloadRecord>,
        ) {
            val expectedByOrdinal = expected.associateBy(TicketScheduleToken::stableOrdinal)
            check(expectedByOrdinal.size == expected.size) { "expected schedule contains duplicate ordinals" }
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

        private fun validateExpectedSchedule(schedule: List<TicketScheduleToken>): List<TicketScheduleToken> {
            require(schedule.isNotEmpty()) { "schedule must not be empty" }
            val ordered = schedule.sortedWith(
                compareBy<TicketScheduleToken> { it.offsetNanos }.thenBy { it.stableOrdinal },
            )
            require(ordered.map { it.stableOrdinal }.sorted() == ordered.indices.toList()) {
                "stable ordinals must be exactly 0 until operationCount"
            }
            require(ordered.none { it.offsetNanos < 0L }) { "schedule offsets must be non-negative" }
            return ordered
        }

        private fun putExactlyOnce(
            records: ConcurrentHashMap<Int, TicketWorkloadRecord>,
            record: TicketWorkloadRecord,
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
                    throw InterruptedException("Ticket scheduler interrupted")
                }
            }
        }

        private fun elapsedSince(startNanos: Long): Long = System.nanoTime() - startNanos
    }
}
