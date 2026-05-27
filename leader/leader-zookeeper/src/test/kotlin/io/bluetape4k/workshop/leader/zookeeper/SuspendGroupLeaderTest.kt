package io.bluetape4k.workshop.leader.zookeeper

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * T5 — Suspend variant of [GroupLeaderTest].
 *
 * Verifies that [io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderGroupElector] admits exactly
 * `maxLeaders` simultaneous coroutine holders.
 *
 * ## Behavior / Contract
 * - Same CountDownLatch handshake as T4, but workers run as coroutines via [SuspendedJobTester].
 * - Orchestrator thread MUST start BEFORE `SuspendedJobTester.run()` — `run()` is blocking
 *   from the calling thread's perspective (the suspend builder is wrapped in `runSuspendIO`).
 * - `CountDownLatch` is safe to use from inside coroutines too — it does not require structured
 *   suspension and provides a deterministic synchronization barrier.
 */
class SuspendGroupLeaderTest: AbstractLeaderZookeeperTest() {

    companion object: KLoggingChannel() {
        private const val MAX_LEADERS = 2
        private const val WORKERS = 4
    }

    @Test
    fun `maxLeaders=2 admits exactly 2 simultaneous suspend holders`(): Unit = runSuspendIO {
        val groupElector = newSuspendGroupElector(MAX_LEADERS)
        val lockName = randomLockName("t5")
        val enteredLatch = CountDownLatch(MAX_LEADERS)
        val releaseLatch = CountDownLatch(1)
        val peakConcurrent = AtomicInteger(0)
        val current = AtomicInteger(0)

        // CRITICAL: orchestrator MUST start BEFORE SuspendedJobTester.run().
        // Same deadlock risk as T4: workers would block forever on releaseLatch.await.
        val orchestrator = Thread {
            check(enteredLatch.await(5, TimeUnit.SECONDS)) {
                "Not enough coroutines entered — CI too slow or maxLeaders not reached"
            }
            releaseLatch.countDown()
        }
        orchestrator.start()

        SuspendedJobTester()
            .workers(WORKERS)
            .rounds(MAX_LEADERS)
            .add {
                groupElector.runIfLeader(lockName) {
                    val c = current.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, c) }
                    try {
                        enteredLatch.countDown()
                        // CountDownLatch.await blocks the carrier thread; acceptable inside the action
                        // because the underlying coroutine is dispatched to a dedicated thread.
                        check(releaseLatch.await(3, TimeUnit.SECONDS)) { "releaseLatch timeout" }
                    } finally {
                        current.decrementAndGet()
                    }
                }
            }
            .run()

        orchestrator.join(6_000)
        peakConcurrent.get() shouldBeEqualTo MAX_LEADERS
    }
}
