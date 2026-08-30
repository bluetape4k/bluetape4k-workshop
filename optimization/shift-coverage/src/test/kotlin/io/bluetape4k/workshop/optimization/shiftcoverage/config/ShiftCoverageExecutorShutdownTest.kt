package io.bluetape4k.workshop.optimization.shiftcoverage.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class ShiftCoverageExecutorShutdownTest {

    @Test
    fun `close closes admission and is idempotent`() {
        val executor = VirtualThreads.executorService()
        val shutdown = ShiftCoverageExecutorShutdown(executor, Duration.ofSeconds(1))

        shutdown.accepting.shouldBeTrue()
        shutdown.close()
        shutdown.close()

        shutdown.accepting.shouldBeFalse()
        assertFailsWith<RejectedExecutionException> { executor.submit {} }
        executor.isTerminated.shouldBeTrue()
    }

    @Test
    fun `close restores interrupt status when await is interrupted`() {
        val interrupted = AtomicBoolean()
        val thread = Thread.ofPlatform().start {
            val executor = VirtualThreads.executorService()
            Thread.currentThread().interrupt()

            ShiftCoverageExecutorShutdown(executor, Duration.ofSeconds(1)).close()

            interrupted.set(Thread.currentThread().isInterrupted)
        }

        thread.join()

        thread.isAlive.shouldBeFalse()
        interrupted.get().shouldBeTrue()
    }
}
