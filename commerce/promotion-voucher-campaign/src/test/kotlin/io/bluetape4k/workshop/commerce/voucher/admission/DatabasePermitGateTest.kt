package io.bluetape4k.workshop.commerce.voucher.admission

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class DatabasePermitGateTest {
    @Test
    fun `cancelled foreground work returns only the local JDBC permit`() {
        val gate = gate()

        assertFailsWith<CancellationException> {
            gate.withPermit(DatabaseLane.FOREGROUND) { throw CancellationException("cancelled") }
        }

        gate.availablePermits(DatabaseLane.FOREGROUND) shouldBeEqualTo 1
        gate.availablePermits(DatabaseLane.WORKER) shouldBeEqualTo 1
        gate.availablePermits(DatabaseLane.SSE_MAINTENANCE) shouldBeEqualTo 1
    }

    @Test
    fun `occupied SSE maintenance lane cannot starve the reserved worker lane`() {
        val gate = gate()
        val sseEntered = CountDownLatch(1)
        val sseRelease = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val sse =
                executor.submit {
                    gate.withPermit(DatabaseLane.SSE_MAINTENANCE) {
                        sseEntered.countDown()
                        sseRelease.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    }
                }
            sseEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

            gate.withPermit(DatabaseLane.WORKER) { "worker-progress" } shouldBeEqualTo "worker-progress"
            sseRelease.countDown()
            sse.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `foreground exhaustion rejects without consuming the worker lane`() {
        val gate = gate()
        val foregroundEntered = CountDownLatch(1)
        val foregroundRelease = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val holder =
                executor.submit {
                    gate.withPermit(DatabaseLane.FOREGROUND) {
                        foregroundEntered.countDown()
                        foregroundRelease.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    }
                }
            foregroundEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

            val failure =
                assertFailsWith<DatabasePermitRejected> {
                    gate.withPermit(DatabaseLane.FOREGROUND) { "unexpected" }
                }
            failure.retryAfter shouldBeEqualTo Duration.ofSeconds(1)
            gate.withPermit(DatabaseLane.WORKER) { "worker-progress" } shouldBeEqualTo "worker-progress"

            foregroundRelease.countDown()
            holder.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `nested acquisition is rejected before another lane is touched`() {
        val gate = gate()

        assertFailsWith<IllegalStateException> {
            gate.withPermit(DatabaseLane.FOREGROUND) {
                gate.withPermit(DatabaseLane.SSE_MAINTENANCE) { "unexpected" }
            }
        }
    }

    @Test
    fun `interruption is converted to a retryable rejection and preserves interrupt status`() {
        val gate = gate(acquireTimeout = Duration.ofSeconds(5))
        val foregroundEntered = CountDownLatch(1)
        val foregroundRelease = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val holder =
                executor.submit {
                    gate.withPermit(DatabaseLane.FOREGROUND) {
                        foregroundEntered.countDown()
                        foregroundRelease.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    }
                }
            foregroundEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

            val interrupted =
                executor.submit<Boolean> {
                    Thread.currentThread().interrupt()
                    assertFailsWith<DatabasePermitRejected> {
                        gate.withPermit(DatabaseLane.FOREGROUND) { "unexpected" }
                    }
                    Thread.currentThread().isInterrupted
                }
            interrupted.get(5, TimeUnit.SECONDS).shouldBeTrue()

            foregroundRelease.countDown()
            holder.get(5, TimeUnit.SECONDS)
        }
    }

    private fun gate(acquireTimeout: Duration = Duration.ofMillis(25)): DatabasePermitGate =
        DatabasePermitGate(
            foregroundPermits = 1,
            workerPermits = 1,
            sseMaintenancePermits = 1,
            acquireTimeout = acquireTimeout,
        )
}
