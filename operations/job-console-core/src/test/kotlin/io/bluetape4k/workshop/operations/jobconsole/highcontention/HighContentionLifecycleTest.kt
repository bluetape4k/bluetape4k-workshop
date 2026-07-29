package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HighContentionLifecycleTest {

    @Test
    fun `deadline composition preserves cleanup and report reserves`() {
        val now = AtomicLong(1_000)
        val deadlines = HighContentionDeadlines.start(
            profileDeadline = Duration.ofNanos(1_000),
            cleanupReserve = Duration.ofNanos(300),
            reportFinalizeReserve = Duration.ofNanos(50),
            cleanupActionBudgets = listOf(Duration.ofNanos(100), Duration.ofNanos(150)),
            runExecutionDeadlineNanos = 1_600,
            nanoTime = now::get,
        )

        deadlines.absoluteProfileDeadlineNanos shouldBeEqualTo 2_000L
        deadlines.profileExecutionDeadlineNanos shouldBeEqualTo 1_700L
        deadlines.cleanupDeadlineNanos shouldBeEqualTo 1_950L
        deadlines.effectivePhaseBudget(Duration.ofNanos(900)) shouldBeEqualTo Duration.ofNanos(600)

        now.set(1_550)
        deadlines.effectivePhaseBudget(Duration.ofNanos(900)) shouldBeEqualTo Duration.ofNanos(50)
        assertFailsWith<IllegalArgumentException> {
            HighContentionDeadlines.start(
                profileDeadline = Duration.ofNanos(1_000),
                cleanupReserve = Duration.ofNanos(250),
                reportFinalizeReserve = Duration.ofNanos(50),
                cleanupActionBudgets = listOf(Duration.ofNanos(100), Duration.ofNanos(150)),
                runExecutionDeadlineNanos = 10_000,
                nanoTime = now::get,
            )
        }
    }

    @Test
    fun `resources are acquired before start and cleaned in reverse exactly once`() {
        val transitions = mutableListOf<HighContentionResourceTransition>()
        val cleanupOrder = mutableListOf<String>()
        val lifecycle = HighContentionLifecycle(
            cleanupDeadlineNanos = Long.MAX_VALUE,
            nanoTime = System::nanoTime,
            transitionObserver = transitions::add,
        )
        val database = lifecycle.allocate("database", Duration.ofSeconds(1)) {
            cleanupOrder += "database"
        }
        val redis = lifecycle.allocate("redis", Duration.ofSeconds(1)) {
            cleanupOrder += "redis"
        }

        lifecycle.start(database) {}
        lifecycle.start(redis) {}
        lifecycle.close()
        lifecycle.close()

        transitions.map { "${it.resourceKey}:${it.state}" } shouldBeEqualTo listOf(
            "database:ALLOCATED",
            "redis:ALLOCATED",
            "database:STARTING",
            "database:STARTED",
            "redis:STARTING",
            "redis:STARTED",
            "redis:CLOSED",
            "database:CLOSED",
        )
        cleanupOrder shouldBeEqualTo listOf("redis", "database")
    }

    @Test
    fun `fail-open barriers release and cleanup errors are suppressed under the primary failure`() {
        val barrier = CountDownLatch(1)
        val lifecycle = HighContentionLifecycle(
            cleanupDeadlineNanos = Long.MAX_VALUE,
            nanoTime = System::nanoTime,
        )
        lifecycle.registerFailOpenBarrier(barrier::countDown)
        lifecycle.allocate("broken", Duration.ofSeconds(1)) {
            error("cleanup failed")
        }
        val primary = IllegalStateException("workload failed")

        val thrown = assertFailsWith<IllegalStateException> {
            lifecycle.finish(primary)
        }

        thrown shouldBeEqualTo primary
        barrier.await(1, TimeUnit.SECONDS) shouldBeEqualTo true
        thrown.suppressed.map { it.message } shouldBeEqualTo listOf("cleanup failed")
    }

    @Test
    fun `transition persistence failure does not skip remaining cleanup`() {
        val cleanupOrder = mutableListOf<String>()
        val lifecycle = HighContentionLifecycle(
            cleanupDeadlineNanos = Long.MAX_VALUE,
            nanoTime = System::nanoTime,
            transitionObserver = { transition ->
                if (
                    transition.resourceKey == "redis" &&
                    transition.state == HighContentionResourceState.CLOSED
                ) {
                    error("journal unavailable")
                }
            },
        )
        lifecycle.allocate("database", Duration.ofSeconds(1)) {
            cleanupOrder += "database"
        }
        lifecycle.allocate("redis", Duration.ofSeconds(1)) {
            cleanupOrder += "redis"
        }

        assertFailsWith<HighContentionCleanupException> {
            lifecycle.close()
        }
        cleanupOrder shouldBeEqualTo listOf("redis", "database")
    }

    @Test
    fun `blocking cleanup is daemon bounded and preserves caller interrupt status`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanupThreadWasDaemon = AtomicBoolean()
        val lifecycle = HighContentionLifecycle(
            cleanupDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1),
            nanoTime = System::nanoTime,
        )
        lifecycle.allocate("blocked", Duration.ofMillis(20)) {
            cleanupThreadWasDaemon.set(Thread.currentThread().isDaemon)
            entered.countDown()
            release.await()
        }
        Thread.currentThread().interrupt()

        try {
            val error = assertFailsWith<HighContentionCleanupException> {
                lifecycle.close()
            }
            error.resourceKey shouldBeEqualTo "blocked"
            cleanupThreadWasDaemon.get() shouldBeEqualTo true
            Thread.currentThread().isInterrupted shouldBeEqualTo true
        } finally {
            release.countDown()
            Thread.interrupted()
            entered.await(1, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `interrupt-ignoring cleanup remains visible to the zero-live gate`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val lifecycle = HighContentionLifecycle(
            cleanupDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1),
            nanoTime = System::nanoTime,
        )
        lifecycle.allocate("stubborn", Duration.ofMillis(20)) {
            entered.countDown()
            try {
                while (release.count > 0) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        // Deliberately model an interrupt-ignoring close action.
                    }
                }
            } finally {
                exited.countDown()
            }
        }

        val error = assertFailsWith<HighContentionCleanupException> {
            lifecycle.close()
        }
        entered.await(1, TimeUnit.SECONDS) shouldBeEqualTo true
        lifecycle.liveCleanupThreadNames() shouldBeEqualTo listOf("high-contention-cleanup-stubborn")
        error.suppressed
            .map { (it as HighContentionCleanupException).resourceKey } shouldBeEqualTo
            listOf("cleanup-thread-leak")

        release.countDown()
        exited.await(1, TimeUnit.SECONDS) shouldBeEqualTo true
        lifecycle.awaitNoLiveCleanupThreads(Duration.ofSeconds(1)) shouldBeEqualTo true
        lifecycle.requireNoLiveCleanupThreads()
    }
}
