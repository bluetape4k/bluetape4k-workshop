package io.bluetape4k.workshop.optimization.shiftcoverage.application

import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** CPU planner admission과 shutdown drain을 소유하는 lifecycle입니다. */
class ShiftCoverageExecutorLifecycle(
    plannerWorkers: Int = 4,
    plannerQueue: Int = 8,
    private val drain: Duration = Duration.ofSeconds(30),
) : AutoCloseable {
    private val accepting = AtomicBoolean(true)
    private val closed = AtomicBoolean(false)
    private val executor: ExecutorService = ThreadPoolExecutor(
        plannerWorkers,
        plannerWorkers,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(plannerQueue),
        Executors.defaultThreadFactory(),
        ThreadPoolExecutor.AbortPolicy(),
    )

    init {
        require(plannerWorkers > 0 && plannerQueue > 0) { "planner executor dimensions must be positive" }
        require(!drain.isNegative) { "drain timeout must not be negative" }
    }

    fun isReady(): Boolean = accepting.get() && !closed.get()

    fun submit(task: Runnable): Boolean {
        if (!accepting.get()) return false
        return try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    /** 결과를 기다리는 planner 호출도 동일한 bounded queue와 rejection 경계를 사용합니다. */
    fun <T> submitCallable(task: Callable<T>): Future<T>? {
        if (!accepting.get()) return null
        val future = FutureTask(task)
        return try {
            executor.execute(future)
            future
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        accepting.set(false)
        executor.shutdown()
        try {
            if (!executor.awaitTermination(drain.toMillis(), TimeUnit.MILLISECONDS)) executor.shutdownNow()
        } catch (interrupted: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
