package io.bluetape4k.workshop.optimization.fieldservice.application

import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.optimization.fieldservice.domain.AggregateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanProposal
import io.bluetape4k.workshop.optimization.fieldservice.planner.DeterministicFieldServicePlanner
import io.bluetape4k.workshop.optimization.fieldservice.planner.PlannerInput
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** CPU planner admission을 bounded하게 제어하며 worker 4개, queue 8개, aggregate별 flight 하나를 허용합니다. */
class FieldServiceReplanService(
    private val planner: DeterministicFieldServicePlanner,
    private val snapshot: (AggregateId) -> PlannerInput,
    private val executor: ThreadPoolExecutor = boundedPlannerExecutor(),
    private val timeout: Duration = Duration.ofSeconds(5),
    private val blockingExecutor: ExecutorService = VirtualThreads.executorService(),
    private val closeBlockingExecutor: Boolean = true,
) : AutoCloseable {
    private val flights = ConcurrentHashMap<AggregateId, CompletableFuture<PlanProposal>>()
    private val tasks = ConcurrentHashMap<AggregateId, ReplanTask>()
    private val plannerPermits = Semaphore(
        executor.maximumPoolSize + executor.queue.remainingCapacity(),
        true,
    )
    private val accepting = AtomicBoolean(true)
    private val admissionLock = ReentrantLock()

    fun requestReplan(aggregateId: AggregateId): ReplanAdmission = admissionLock.withLock {
        if (!accepting.get()) return@withLock ReplanAdmission.Rejected("REPLAN_REJECTED")
        flights[aggregateId]?.let { existing ->
            if (!existing.isDone) return@withLock ReplanAdmission.Coalesced(existing)
            tasks[aggregateId]?.let { cleanup(aggregateId, it) }
                ?: flights.remove(aggregateId, existing)
        }
        if (!plannerPermits.tryAcquire()) return@withLock ReplanAdmission.Rejected("REPLAN_REJECTED")
        lateinit var task: ReplanTask
        val result = ReplanFuture {
            task.cancelStages(it)
            cleanup(aggregateId, task)
        }
        val snapshotLease = StageLease()
        val snapshotTask = object : FutureTask<PlannerInput>(Callable {
            if (!snapshotLease.tryStart()) throw CancellationException("snapshot stage cancelled before start")
            try {
                snapshot(aggregateId)
            } finally {
                snapshotLease.finish()
            }
        }) {
            override fun done() {
                if (result.isDone) {
                    cleanup(aggregateId, task)
                    return
                }
                try {
                    val input = get()
                    val planningLease = task.registerStage()
                    if (planningLease == null) {
                        cleanup(aggregateId, task)
                        return
                    }
                    val planningTask = object : FutureTask<PlanProposal>(Callable {
                        if (!planningLease.tryStart()) {
                            throw CancellationException("planning stage cancelled before start")
                        }
                        try {
                            planner.plan(input)
                        } finally {
                            planningLease.finish()
                        }
                    }) {
                        override fun done() {
                            if (result.isDone) {
                                cleanup(aggregateId, task)
                                return
                            }
                            try {
                                result.complete(get())
                            } catch (failure: Throwable) {
                                result.completeExceptionally(unwrap(failure))
                            } finally {
                                cleanup(aggregateId, task)
                            }
                        }
                    }
                    task.plannerTask = planningTask
                    task.plannerLease = planningLease
                    if (result.isDone) {
                        task.cancelStages()
                        planningTask.cancel(true)
                        cleanup(aggregateId, task)
                    } else {
                        try {
                            executor.execute(planningTask)
                        } catch (rejected: RejectedExecutionException) {
                            // The planner task was never accepted, so cancel its lease before exposing the failure.
                            task.plannerLease?.cancel()
                            cleanup(aggregateId, task)
                            result.completeExceptionally(rejected)
                        }
                    }
                } catch (failure: Throwable) {
                    result.completeExceptionally(unwrap(failure))
                    cleanup(aggregateId, task)
                }
            }
        }
        task = ReplanTask(result, snapshotTask, snapshotLease, plannerPermits)
        flights[aggregateId] = result
        tasks[aggregateId] = task
        return@withLock try {
            blockingExecutor.execute(snapshotTask)
            CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute {
                if (result.completeExceptionally(TimeoutException("replan snapshot timed out"))) {
                    task.cancelStages()
                    cleanup(aggregateId, task)
                }
            }
            ReplanAdmission.Accepted(result)
        } catch (rejected: RejectedExecutionException) {
            task.cancelStages()
            cleanup(aggregateId, task)
            log.warn { "Field Service replan queue rejected aggregate=${aggregateId.value}" }
            ReplanAdmission.Rejected("REPLAN_REJECTED")
        }
    }

    fun await(admission: ReplanAdmission): PlanProposal? = when (admission) {
        is ReplanAdmission.Accepted -> awaitFuture(admission.future)
        is ReplanAdmission.Coalesced -> awaitFuture(admission.future)
        is ReplanAdmission.Rejected -> null
    }

    private fun awaitFuture(future: Future<PlanProposal>): PlanProposal? = try {
        future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (timeout: TimeoutException) {
        cancelFuture(future)
        throw timeout
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        cancelFuture(future)
        throw interrupted
    } catch (failure: ExecutionException) {
        if (unwrap(failure) is TimeoutException) {
            cancelFuture(future)
            throw unwrap(failure)
        }
        throw failure
    }

    override fun close() {
        val shutdownDeadlineNanos = shutdownDeadlineNanos()
        admissionLock.withLock {
            accepting.set(false)
            tasks.entries.forEach { (aggregateId, task) ->
                task.result.cancel(true)
                task.cancelStages()
                cleanup(aggregateId, task)
            }
            flights.clear()
        }

        var interrupted = false
        executor.shutdown()
        try {
            if (!awaitTermination(executor, shutdownDeadlineNanos)) {
                tasks.values.forEach { it.cancelStages() }
                executor.shutdownNow()
            }
        } catch (failure: InterruptedException) {
            interrupted = true
            tasks.values.forEach { it.cancelStages() }
            executor.shutdownNow()
            log.warn(failure) { "Field Service planner executor shutdown was interrupted" }
        }
        if (closeBlockingExecutor) {
            blockingExecutor.shutdown()
            try {
                if (!awaitTermination(blockingExecutor, shutdownDeadlineNanos)) {
                    blockingExecutor.shutdownNow()
                }
            } catch (failure: InterruptedException) {
                interrupted = true
                blockingExecutor.shutdownNow()
                log.warn(failure) { "Field Service blocking executor shutdown was interrupted" }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun shutdownDeadlineNanos(): Long {
        val now = System.nanoTime()
        val timeoutNanos = TimeUnit.SECONDS.toNanos(CLOSE_TIMEOUT_SECONDS)
        return if (now > Long.MAX_VALUE - timeoutNanos) Long.MAX_VALUE else now + timeoutNanos
    }

    private fun awaitTermination(executor: ExecutorService, deadlineNanos: Long): Boolean {
        val remainingNanos = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
        return remainingNanos > 0L && executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)
    }

    private fun cancelFuture(future: Future<PlanProposal>) {
        future.cancel(true)
    }

    private fun cleanup(aggregateId: AggregateId, task: ReplanTask) {
        tasks.remove(aggregateId, task)
        flights.remove(aggregateId, task.result)
        task.requestCleanup()
    }

    private fun unwrap(failure: Throwable): Throwable =
        if (failure is ExecutionException) failure.cause ?: failure else failure

    private class ReplanTask(
        val result: CompletableFuture<PlanProposal>,
        val snapshotTask: FutureTask<PlannerInput>,
        private val snapshotLease: StageLease,
        private val plannerPermits: Semaphore,
    ) {
        @Volatile
        var plannerTask: FutureTask<PlanProposal>? = null

        @Volatile
        var plannerLease: StageLease? = null

        private val leaseLock = Any()
        private var openStages = 1
        private var cleanupRequested = false
        private val permitReleased = AtomicBoolean(false)

        init {
            snapshotLease.bind(::stageDone)
        }

        fun registerStage(): StageLease? = synchronized(leaseLock) {
            if (cleanupRequested) return@synchronized null
            openStages += 1
            StageLease().also { it.bind(::stageDone) }
        }

        private fun stageDone() {
            val shouldRelease = synchronized(leaseLock) {
                openStages -= 1
                openStages == 0 && cleanupRequested
            }
            if (shouldRelease) releasePermit()
        }

        fun cancelStages(mayInterruptIfRunning: Boolean = true) {
            plannerLease?.cancel()
            snapshotLease.cancel()
            plannerTask?.cancel(mayInterruptIfRunning)
            snapshotTask.cancel(mayInterruptIfRunning)
        }

        fun requestCleanup() {
            val shouldRelease = synchronized(leaseLock) {
                cleanupRequested = true
                openStages == 0
            }
            if (shouldRelease) releasePermit()
        }

        private fun releasePermit() {
            if (permitReleased.compareAndSet(false, true)) {
                plannerPermits.release()
            }
        }
    }

    private class StageLease {
        private enum class State { QUEUED, RUNNING, DONE }

        private val state = AtomicReference(State.QUEUED)

        @Volatile
        private var onDone: (() -> Unit)? = null

        fun bind(onDone: () -> Unit) {
            this.onDone = onDone
        }

        fun tryStart(): Boolean = state.compareAndSet(State.QUEUED, State.RUNNING)

        fun cancel() {
            if (state.compareAndSet(State.QUEUED, State.DONE)) notifyDone()
        }

        fun finish() {
            if (state.getAndSet(State.DONE) != State.DONE) notifyDone()
        }

        private fun notifyDone() {
            checkNotNull(onDone).invoke()
        }
    }

    private class ReplanFuture(
        private val onCancel: (Boolean) -> Unit,
    ) : CompletableFuture<PlanProposal>() {
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            val cancelled = super.cancel(mayInterruptIfRunning)
            if (cancelled) onCancel(mayInterruptIfRunning)
            return cancelled
        }
    }

    companion object : KLogging() {
        const val CPU_WORKERS: Int = 4
        const val QUEUE_CAPACITY: Int = 8
        private const val CLOSE_TIMEOUT_SECONDS = 30L

        fun boundedPlannerExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(
            CPU_WORKERS,
            CPU_WORKERS,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(QUEUE_CAPACITY),
            ThreadPoolExecutor.AbortPolicy(),
        )
    }
}

sealed interface ReplanAdmission {
    data class Accepted(val future: Future<PlanProposal>) : ReplanAdmission
    data class Coalesced(val future: Future<PlanProposal>) : ReplanAdmission
    data class Rejected(val code: String, val httpStatus: Int = 429) : ReplanAdmission
}
