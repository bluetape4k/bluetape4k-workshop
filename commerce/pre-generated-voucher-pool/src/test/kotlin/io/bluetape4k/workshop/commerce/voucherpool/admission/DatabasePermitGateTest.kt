package io.bluetape4k.workshop.commerce.voucherpool.admission

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class DatabasePermitGateTest {
    @Test
    fun `foreground timeout releases only the waiting acquisition`() {
        val gate = gate()
        val holderEntered = CountDownLatch(1)
        val holderRelease = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val holder =
                executor.submit {
                    gate.withForegroundPermit {
                        holderEntered.countDown()
                        holderRelease.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    }
                }
            holderEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

            val failure =
                assertFailsWith<PoolBusyException> {
                    val result = gate.withForegroundPermit { "unexpected" }
                    error("foreground permit was acquired unexpectedly: $result")
                }
            failure.lane shouldBeEqualTo PermitLane.FOREGROUND
            gate.snapshot().foregroundInUse shouldBeEqualTo 1
            gate.snapshot().workerInUse shouldBeEqualTo 0
            gate.snapshot().sseInUse shouldBeEqualTo 0

            holderRelease.countDown()
            holder.get(5, TimeUnit.SECONDS)
        }

        gate.snapshot().foregroundInUse shouldBeEqualTo 0
    }

    @Test
    fun `default lane budgets fit Hikari and nested acquisition fails immediately`() {
        val gate = DatabasePermitGate.default(hikariMaximumPoolSize = 16)

        gate.snapshot().capacities shouldBeEqualTo
            mapOf(
                PermitLane.FOREGROUND to 12,
                PermitLane.WORKER to 1,
                PermitLane.SSE to 3,
            )
        gate.snapshot().waits shouldBeEqualTo
            mapOf(
                PermitLane.FOREGROUND to 250.milliseconds,
                PermitLane.WORKER to 1.seconds,
                PermitLane.SSE to 1.seconds,
            )

        gate.withForegroundPermit {
            val failure =
                assertFailsWith<NestedPermitException> {
                    val result = gate.withSsePermit { "unexpected" }
                    error("nested permit was acquired unexpectedly: $result")
                }
            failure.activeLane shouldBeEqualTo PermitLane.FOREGROUND
            failure.requestedLane shouldBeEqualTo PermitLane.SSE
            gate.snapshot().sseInUse shouldBeEqualTo 0
        }
    }

    @Test
    fun `invalid lane configuration is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DatabasePermitGate(
                hikariMaximumPoolSize = 3,
                configs = mapOf(PermitLane.FOREGROUND to PermitLaneConfig(1, 25.milliseconds)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DatabasePermitGate(
                hikariMaximumPoolSize = 3,
                configs =
                    mapOf(
                        PermitLane.FOREGROUND to PermitLaneConfig(0, 25.milliseconds),
                        PermitLane.WORKER to PermitLaneConfig(1, 1.seconds),
                        PermitLane.SSE to PermitLaneConfig(1, 1.seconds),
                    ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DatabasePermitGate(
                hikariMaximumPoolSize = 2,
                configs =
                    mapOf(
                        PermitLane.FOREGROUND to PermitLaneConfig(1, 25.milliseconds),
                        PermitLane.WORKER to PermitLaneConfig(1, 1.seconds),
                        PermitLane.SSE to PermitLaneConfig(1, 1.seconds),
                    ),
            )
        }
    }

    @Test
    fun `exception releases the acquired lane without changing another lane`() {
        val gate = gate()

        assertFailsWith<IllegalStateException> {
            gate.withWorkerPermit { throw IllegalStateException("worker failed") }
        }

        gate.snapshot().workerInUse shouldBeEqualTo 0
        gate.snapshot().foregroundInUse shouldBeEqualTo 0
        gate.snapshot().sseInUse shouldBeEqualTo 0
    }

    @Test
    fun `interruption preserves the holder and restores interrupt status`() {
        val gate = gate(workerWaitSeconds = 5)
        val holderEntered = CountDownLatch(1)
        val holderRelease = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val holder =
                executor.submit {
                    gate.withWorkerPermit {
                        holderEntered.countDown()
                        holderRelease.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    }
                }
            holderEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

            val interrupted =
                executor.submit<Boolean> {
                    Thread.currentThread().interrupt()
                    val failure =
                        assertFailsWith<PoolBusyException> {
                            val result = gate.withWorkerPermit { "unexpected" }
                            error("interrupted permit was acquired unexpectedly: $result")
                        }
                    failure.interrupted.shouldBeTrue()
                    Thread.currentThread().isInterrupted
                }
            interrupted.get(5, TimeUnit.SECONDS).shouldBeTrue()
            gate.snapshot().workerInUse shouldBeEqualTo 1

            holderRelease.countDown()
            holder.get(5, TimeUnit.SECONDS)
        }

        gate.snapshot().workerInUse shouldBeEqualTo 0
        Thread.currentThread().isInterrupted.shouldBeFalse()
    }

    @Test
    fun `cancellation after acquisition releases only the acquired lane`() {
        val gate = gate()
        val foregroundEntered = CountDownLatch(1)
        val foregroundRelease = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val foregroundHolder =
                executor.submit {
                    gate.withForegroundPermit {
                        foregroundEntered.countDown()
                        foregroundRelease.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    }
                }
            foregroundEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()
            try {
                assertFailsWith<CancellationException> {
                    gate.withWorkerPermit {
                        gate.snapshot().foregroundInUse shouldBeEqualTo 1
                        gate.snapshot().workerInUse shouldBeEqualTo 1
                        throw CancellationException("worker cancelled")
                    }
                }

                gate.snapshot().foregroundInUse shouldBeEqualTo 1
                gate.snapshot().workerInUse shouldBeEqualTo 0
                gate.snapshot().sseInUse shouldBeEqualTo 0
            } finally {
                foregroundRelease.countDown()
                foregroundHolder.get(5, TimeUnit.SECONDS)
            }
        }

        gate.snapshot().foregroundInUse shouldBeEqualTo 0
    }

    private fun gate(workerWaitSeconds: Int = 1): DatabasePermitGate =
        DatabasePermitGate(
            hikariMaximumPoolSize = 3,
            configs =
                mapOf(
                    PermitLane.FOREGROUND to PermitLaneConfig(1, 25.milliseconds),
                    PermitLane.WORKER to PermitLaneConfig(1, workerWaitSeconds.seconds),
                    PermitLane.SSE to PermitLaneConfig(1, 1.seconds),
                ),
        )
}
