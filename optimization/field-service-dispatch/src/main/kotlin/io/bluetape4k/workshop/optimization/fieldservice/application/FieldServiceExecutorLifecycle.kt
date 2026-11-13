package io.bluetape4k.workshop.optimization.fieldservice.application

import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.logging.KLogging
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/** demo worker의 admission 종료, quiescence, executor drain lifecycle입니다. */
class FieldServiceExecutorLifecycle(
    private val executor: ExecutorService,
    private val shutdownTimeoutSeconds: Long = 30L,
): AutoCloseable {

    companion object: KLogging()

    private val admissionOpen = AtomicBoolean(true)

    @Volatile
    var shutdownTimedOut: Boolean = false
        private set

    val accepting: Boolean
        get() = admissionOpen.get()

    val executorTerminated: Boolean
        get() = executor.isTerminated

    fun <T> submit(block: () -> T): T {
        check(admissionOpen.get()) { "REPLAN_REJECTED" }
        return executor.submit<T> { block() }.get()
    }

    override fun close() {
        if (!admissionOpen.compareAndSet(true, false)) return
        executor.shutdown()
        if (!executor.awaitTermination(shutdownTimeoutSeconds.seconds)) {
            shutdownTimedOut = true
            executor.shutdownNow()
        }
    }
}
