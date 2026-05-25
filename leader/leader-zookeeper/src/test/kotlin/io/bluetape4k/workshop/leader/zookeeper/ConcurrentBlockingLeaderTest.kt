package io.bluetape4k.workshop.leader.zookeeper

import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * T2: Concurrent blocking leader election — at most one worker inside the action body.
 *
 * 8 workers compete for a single shared [io.bluetape4k.leader.zookeeper.ZooKeeperLeaderElector]
 * using the shared curator from [AbstractLeaderZookeeperTest].
 *
 * ## Behavior verified
 * - `peakConcurrent` (the maximum number of workers simultaneously inside the action body)
 *   never exceeds 1 — i.e., the lock provides strict mutual exclusion.
 * - At least 3 of the 8 workers eventually execute the action successfully (proving that
 *   the lock is released between workers and that the wait window admits multiple wins).
 *
 * Uses [MultithreadingTester] (bluetape4k-junit5) — raw Thread/Executors/CyclicBarrier forbidden.
 */
class ConcurrentBlockingLeaderTest : AbstractLeaderZookeeperTest() {

    @Test
    fun `peakConcurrent never exceeds 1 across 8 concurrent workers`() {
        val lockName = randomLockName("t2")
        // Shared single elector created ONCE outside the worker block — workers compete on it.
        val elector = newElector()

        val executed = AtomicInteger(0)
        val current = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        MultithreadingTester()
            .workers(8)
            .rounds(1)
            .add {
                elector.runIfLeader(lockName) {
                    val c = current.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, c) }
                    try {
                        executed.incrementAndGet()
                        // Hold the lock briefly so other workers actually contend, but short
                        // enough that several can serialize through within their 500ms waitTime.
                        Thread.sleep(30)
                    } finally {
                        current.decrementAndGet()
                    }
                }
            }
            .run()

        peakConcurrent.get() shouldBeLessOrEqualTo 1
        executed.get() shouldBeGreaterThan 3
    }
}
