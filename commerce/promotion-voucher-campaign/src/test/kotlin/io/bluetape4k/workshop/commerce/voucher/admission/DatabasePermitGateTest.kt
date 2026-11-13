package io.bluetape4k.workshop.commerce.voucher.admission

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

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
                        sseRelease.await(5.seconds).shouldBeTrue()
                    }
                }
            sseEntered.await(5.seconds).shouldBeTrue()

            gate.withPermit(DatabaseLane.WORKER) { "worker-progress" } shouldBeEqualTo "worker-progress"
            sseRelease.countDown()
            sse.get(5.seconds)
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
                        foregroundRelease.await(5.seconds).shouldBeTrue()
                    }
                }
            foregroundEntered.await(5.seconds).shouldBeTrue()

            val failure =
                assertFailsWith<DatabasePermitRejected> {
                    val result = gate.withPermit(DatabaseLane.FOREGROUND) { Unit }
                    error("foreground permit was acquired unexpectedly: $result")
                }
            failure.retryAfter shouldBeEqualTo 1.seconds()
            gate.withPermit(DatabaseLane.WORKER) { "worker-progress" } shouldBeEqualTo "worker-progress"

            foregroundRelease.countDown()
            holder.get(5.seconds)
        }
    }

    @Test
    fun `lane probes expose active and queued virtual threads without consuming permits`() {
        val gate = gate(acquireTimeout = 5.seconds())
        val foregroundEntered = CountDownLatch(1)
        val foregroundRelease = CountDownLatch(1)
        val waiterStarted = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val holder =
                executor.submit {
                    gate.withPermit(DatabaseLane.FOREGROUND) {
                        foregroundEntered.countDown()
                        foregroundRelease.await(5.seconds).shouldBeTrue()
                    }
                }
            foregroundEntered.await(5.seconds).shouldBeTrue()
            val waiter =
                executor.submit<String> {
                    waiterStarted.countDown()
                    gate.withPermit(DatabaseLane.FOREGROUND) { "queued-progress" }
                }
            waiterStarted.await(5.seconds).shouldBeTrue()

            awaitQueued(gate)
            gate.inUsePermits(DatabaseLane.FOREGROUND) shouldBeEqualTo 1
            gate.waitingThreads(DatabaseLane.FOREGROUND) shouldBeEqualTo 1
            gate.availablePermits(DatabaseLane.WORKER) shouldBeEqualTo 1

            foregroundRelease.countDown()
            holder.get(5.seconds)
            waiter.get(5.seconds) shouldBeEqualTo "queued-progress"
            gate.inUsePermits(DatabaseLane.FOREGROUND) shouldBeEqualTo 0
            gate.waitingThreads(DatabaseLane.FOREGROUND) shouldBeEqualTo 0
        }
    }

    @Test
    fun `nested acquisition is rejected before another lane is touched`() {
        val gate = gate()

        assertFailsWith<IllegalStateException> {
            val result =
                gate.withPermit(DatabaseLane.FOREGROUND) {
                    val nestedResult = gate.withPermit(DatabaseLane.SSE_MAINTENANCE) { Unit }
                    error("nested permit was acquired unexpectedly: $nestedResult")
                }
            error("outer permit completed unexpectedly: $result")
        }
    }

    @Test
    fun `interruption is converted to a retryable rejection and preserves interrupt status`() {
        val gate = gate(acquireTimeout = 5.seconds())
        val foregroundEntered = CountDownLatch(1)
        val foregroundRelease = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val holder =
                executor.submit {
                    gate.withPermit(DatabaseLane.FOREGROUND) {
                        foregroundEntered.countDown()
                        foregroundRelease.await(5.seconds).shouldBeTrue()
                    }
                }
            foregroundEntered.await(5.seconds).shouldBeTrue()

            val interrupted =
                executor.submit<Boolean> {
                    Thread.currentThread().interrupt()
                    assertFailsWith<DatabasePermitRejected> {
                        val result = gate.withPermit(DatabaseLane.FOREGROUND) { Unit }
                        error("interrupted permit was acquired unexpectedly: $result")
                    }
                    Thread.currentThread().isInterrupted
                }
            interrupted.get(5.seconds).shouldBeTrue()

            foregroundRelease.countDown()
            holder.get(5.seconds)
        }
    }

    private fun gate(acquireTimeout: Duration = 25.millis()): DatabasePermitGate =
        DatabasePermitGate(
            foregroundPermits = 1,
            workerPermits = 1,
            sseMaintenancePermits = 1,
            acquireTimeout = acquireTimeout,
        )

    private fun awaitQueued(gate: DatabasePermitGate) {
        val deadline = System.nanoTime() + 2.seconds().toNanos()
        while (gate.waitingThreads(DatabaseLane.FOREGROUND) == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        gate.waitingThreads(DatabaseLane.FOREGROUND) shouldBeEqualTo 1
    }
}
