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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private val tasks = ConcurrentHashMap<AggregateId, FutureTask<Unit>>()
    private val accepting = AtomicBoolean(true)

    fun requestReplan(aggregateId: AggregateId): ReplanAdmission {
        if (!accepting.get()) return ReplanAdmission.Rejected("REPLAN_REJECTED")
        synchronized(flights) {
            flights[aggregateId]?.let { existing ->
                if (!existing.isDone) return ReplanAdmission.Coalesced(existing)
                flights.remove(aggregateId, existing)
            }
            val result = CompletableFuture<PlanProposal>()
            if (flights.putIfAbsent(aggregateId, result) != null) {
                return ReplanAdmission.Coalesced(flights.getValue(aggregateId))
            }
            lateinit var task: FutureTask<Unit>
            var snapshotTask: Future<PlannerInput>? = null
            task = FutureTask {
                try {
                    snapshotTask = blockingExecutor.submit(Callable { snapshot(aggregateId) })
                    val input = snapshotTask?.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                        ?: throw IllegalStateException("snapshot task was not submitted")
                    result.complete(planner.plan(input))
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                } finally {
                    snapshotTask?.cancel(true)
                    tasks.remove(aggregateId, task)
                    flights.remove(aggregateId, result)
                }
            }
            tasks[aggregateId] = task
            return try {
                executor.execute(task)
                ReplanAdmission.Accepted(result)
            } catch (rejected: RejectedExecutionException) {
                tasks.remove(aggregateId, task)
                flights.remove(aggregateId, result)
                log.warn { "Field Service replan queue rejected aggregate=${aggregateId.value}" }
                ReplanAdmission.Rejected("REPLAN_REJECTED")
            }
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
        future.cancel(true)
        flights.entries.firstOrNull { it.value === future }?.key?.let { aggregateId ->
            tasks.remove(aggregateId)?.cancel(true)
            flights.remove(aggregateId, future as CompletableFuture<PlanProposal>)
        }
        throw timeout
    }

    override fun close() {
        accepting.set(false)
        executor.shutdown()
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            tasks.values.forEach { it.cancel(true) }
            executor.shutdownNow()
        }
        tasks.values.forEach { it.cancel(true) }
        flights.clear()
        if (closeBlockingExecutor) {
            blockingExecutor.shutdown()
            if (!blockingExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                blockingExecutor.shutdownNow()
            }
        }
    }

    companion object : KLogging() {
        const val CPU_WORKERS: Int = 4
        const val QUEUE_CAPACITY: Int = 8

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
