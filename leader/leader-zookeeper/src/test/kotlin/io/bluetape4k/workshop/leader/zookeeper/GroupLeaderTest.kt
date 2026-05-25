package io.bluetape4k.workshop.leader.zookeeper

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * T4 — Verifies that the ZooKeeper group-leader elector admits exactly [maxLeaders] simultaneous
 * holders under concurrent contention.
 *
 * ## Behavior / Contract
 * - Uses a `CountDownLatch(maxLeaders)` handshake to assert peak concurrency *inside* the action body.
 *   Sampling after `MultithreadingTester.run()` returns is too late — by then all holders have released.
 * - The orchestrator thread MUST start BEFORE `MultithreadingTester.run()` (which is blocking).
 *   If it starts after `run()`, workers would block forever on `releaseLatch.await` since
 *   `run()` only returns after every worker finishes — classic deadlock.
 * - `peakConcurrent` is updated via `AtomicInteger.updateAndGet(max(...))` to safely sample the
 *   maximum across worker threads.
 */
class GroupLeaderTest: AbstractLeaderZookeeperTest() {

    companion object: io.bluetape4k.logging.KLogging() {
        private const val MAX_LEADERS = 2
        private const val WORKERS = 4
    }

    @Test
    fun `maxLeaders=2 admits exactly 2 simultaneous holders`() {
        val groupElector = newGroupElector(MAX_LEADERS)
        val lockName = randomLockName("t4")
        val enteredLatch = CountDownLatch(MAX_LEADERS)
        val releaseLatch = CountDownLatch(1)
        val peakConcurrent = AtomicInteger(0)
        val current = AtomicInteger(0)

        // CRITICAL: orchestrator MUST start BEFORE MultithreadingTester.run() (which is blocking).
        // Otherwise workers would block forever on releaseLatch.await, causing deadlock.
        val orchestrator = Thread {
            check(enteredLatch.await(5, TimeUnit.SECONDS)) {
                "Not enough workers entered — CI too slow or maxLeaders not reached"
            }
            releaseLatch.countDown()
        }
        orchestrator.start()

        MultithreadingTester()
            .workers(WORKERS)
            .rounds(1)
            .add {
                groupElector.runIfLeader(lockName) {
                    val c = current.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, c) }
                    try {
                        enteredLatch.countDown()
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
