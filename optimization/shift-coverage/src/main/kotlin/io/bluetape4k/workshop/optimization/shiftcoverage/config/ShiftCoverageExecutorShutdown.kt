package io.bluetape4k.workshop.optimization.shiftcoverage.config

import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.toKotlinDuration

/** Owns the virtual-thread executor lifecycle for the demo module. */
class ShiftCoverageExecutorShutdown(
    private val executor: ExecutorService,
    private val drainTimeout: Duration,
): AutoCloseable {

    private companion object: KLogging()

    private val closed = AtomicBoolean(false)

    @Volatile
    var accepting: Boolean = true
        private set

    init {
        drainTimeout.requirePositiveDuration("drainTimeout")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        accepting = false
        executor.shutdown()
        try {
            if (!executor.awaitTermination(drainTimeout.toKotlinDuration())) {
                executor.shutdownNow()
            }
        } catch (interrupted: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
            log.warn(interrupted) { "Shift coverage executor shutdown was interrupted" }
        }
    }
}
