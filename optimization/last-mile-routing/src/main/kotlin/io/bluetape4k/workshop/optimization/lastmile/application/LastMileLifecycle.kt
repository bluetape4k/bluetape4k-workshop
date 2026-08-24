package io.bluetape4k.workshop.optimization.lastmile.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Java 25 virtual-thread admission fence와 graceful shutdown을 한 곳에서 관리합니다. */
internal class LastMileLifecycle(
    private val executor: ExecutorService,
    private val shutdownTimeout: Long = 30L,
) : AutoCloseable {
    private val admissionOpen = AtomicBoolean(true)

    val accepting: Boolean
        get() = admissionOpen.get()

    fun <T> submit(task: () -> T): Future<T> {
        check(admissionOpen.get()) { "last-mile lifecycle is shutting down" }
        return executor.submit(Callable(task))
    }

    override fun close() {
        if (!admissionOpen.compareAndSet(true, false)) return
        executor.shutdown()
        if (!executor.awaitTermination(shutdownTimeout, TimeUnit.SECONDS)) {
            log.warn { "last-mile virtual-thread executor did not drain before timeout" }
            executor.shutdownNow()
        }
    }

    companion object : KLogging()
}
