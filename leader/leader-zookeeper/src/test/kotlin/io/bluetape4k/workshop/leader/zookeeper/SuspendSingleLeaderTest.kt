package io.bluetape4k.workshop.leader.zookeeper

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * T3: Single-coroutine and concurrent suspend leader election tests for
 * [io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderElector].
 *
 * ## Behavior verified
 * - A single suspending caller wins leadership and the action returns `"done"`.
 * - When 8 coroutines compete via [SuspendedJobTester] using a shared elector,
 *   `peakConcurrent` never exceeds 1 (strict mutual exclusion) and at least 3
 *   coroutines eventually execute the action body.
 *
 * NOTE: The concurrent test uses `runSuspendIO` (NOT `runTest`) because real ZooKeeper
 * network I/O does not cooperate with the test virtual-time scheduler used by `runTest`.
 */
class SuspendSingleLeaderTest : AbstractLeaderZookeeperTest() {

    @Test
    fun `runIfLeader returns done for a single suspending caller`() = runSuspendIO {
        val elector = newSuspendElector()

        val result = elector.runIfLeader(randomLockName("t3-single")) { "done" }

        result.shouldNotBeNull() shouldBeEqualTo "done"
    }

    @Test
    fun `peakConcurrent never exceeds 1 across 8 concurrent coroutines`(): Unit = runSuspendIO {
        val lockName = randomLockName("t3-concurrent")
        // Shared single suspend elector created ONCE outside the worker block.
        val elector = newSuspendElector()

        val executed = AtomicInteger(0)
        val current = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        SuspendedJobTester()
            .workers(8)
            .rounds(8)
            .add {
                elector.runIfLeader(lockName) {
                    val c = current.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, c) }
                    try {
                        executed.incrementAndGet()
                        // Hold briefly so workers contend yet several can serialize through.
                        delay(30)
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
