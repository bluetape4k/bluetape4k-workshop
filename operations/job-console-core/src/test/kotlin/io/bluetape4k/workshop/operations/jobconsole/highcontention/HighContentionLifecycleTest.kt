package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

class HighContentionLifecycleTest {

    companion object: KLogging()

    @Test
    fun `deadline composition preserves cleanup and report reserves`() {
        val now = AtomicLong(1_000)
        val deadlines = HighContentionDeadlines.start(
            profileDeadline = 1_000.nanos(),
            cleanupReserve = 300.nanos(),
            reportFinalizeReserve = 50.nanos(),
            cleanupActionBudgets = listOf(100.nanos(), 150.nanos()),
            runExecutionDeadlineNanos = 1_600,
            nanoTime = now::get,
        )

        log.debug { "deadlines=$deadlines" }
        deadlines.absoluteProfileDeadlineNanos shouldBeEqualTo 2_000L
        deadlines.profileExecutionDeadlineNanos shouldBeEqualTo 1_700L
        deadlines.cleanupDeadlineNanos shouldBeEqualTo 1_950L
        deadlines.effectivePhaseBudget(900.nanos()) shouldBeEqualTo 600.nanos()

        now.set(1_550)
        deadlines.effectivePhaseBudget(900.nanos()) shouldBeEqualTo 50.nanos()
        
        assertFailsWith<IllegalArgumentException> {
            HighContentionDeadlines.start(
                profileDeadline = 1_000.nanos(),
                cleanupReserve = 250.nanos(),
                reportFinalizeReserve = 50.nanos(),
                cleanupActionBudgets = listOf(100.nanos(), 150.nanos()),
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
        lifecycle.allocate("broken", 1.seconds()) {
            error("cleanup failed")
        }
        val primary = IllegalStateException("workload failed")

        val thrown = assertFailsWith<IllegalStateException> {
            lifecycle.finish(primary)
        }

        thrown shouldBeEqualTo primary
        barrier.await(1.seconds).shouldBeTrue()
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
            cleanupDeadlineNanos = System.nanoTime() + 1.seconds().toNanos(),
            nanoTime = System::nanoTime,
        )
        lifecycle.allocate("blocked", 20.millis()) {
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
            cleanupThreadWasDaemon.get().shouldBeTrue()
            Thread.currentThread().isInterrupted.shouldBeTrue()
        } finally {
            release.countDown()
            Thread.interrupted()
            entered.await(1.seconds)
        }
    }

    @Test
    fun `interrupt-ignoring cleanup remains visible to the zero-live gate`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val lifecycle = HighContentionLifecycle(
            cleanupDeadlineNanos = System.nanoTime() + 1.seconds().toNanos(),
            nanoTime = System::nanoTime,
        )
        lifecycle.allocate("stubborn", 20.millis()) {
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
        entered.await(1.seconds).shouldBeTrue()

        lifecycle.liveCleanupThreadNames() shouldBeEqualTo listOf("high-contention-cleanup-stubborn")
        error.suppressed
            .map { (it as HighContentionCleanupException).resourceKey } shouldBeEqualTo
            listOf("cleanup-thread-leak")

        release.countDown()
        exited.await(1.seconds).shouldBeTrue()
        lifecycle.awaitNoLiveCleanupThreads(1.seconds()).shouldBeTrue()
        lifecycle.requireNoLiveCleanupThreads()
    }
}
