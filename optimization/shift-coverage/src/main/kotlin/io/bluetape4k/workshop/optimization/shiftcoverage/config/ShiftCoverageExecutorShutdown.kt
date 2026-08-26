package io.bluetape4k.workshop.optimization.shiftcoverage.config

import io.bluetape4k.logging.KLogging
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the virtual-thread executor lifecycle for the demo module. */
class ShiftCoverageExecutorShutdown(
    private val executor: ExecutorService,
    private val drainTimeout: Duration,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    @Volatile
    var accepting: Boolean = true
        private set

    init {
        require(!drainTimeout.isZero && !drainTimeout.isNegative) {
            "drainTimeout must be positive"
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        accepting = false
        executor.shutdown()
        try {
            if (!executor.awaitTermination(drainTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow()
            }
        } catch (interrupted: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
            log.warn("Shift coverage executor shutdown was interrupted", interrupted)
        }
    }

    private companion object : KLogging()
}
