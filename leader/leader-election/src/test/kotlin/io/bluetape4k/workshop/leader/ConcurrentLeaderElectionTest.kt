package io.bluetape4k.workshop.leader

import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * T2: Concurrent leader election — exactly one winner among N instances.
 *
 * Runs 3 workers in parallel, each with its own Lettuce connection and [LettuceLeaderElector].
 * Asserts that:
 * 1. All 3 workers attempted `runIfLeader` (attempt count = 3).
 * 2. Exactly 1 worker executed the action body (execution count = 1).
 *
 * Uses [MultithreadingTester] (bluetape4k-junit5) — raw Thread/Executors/CyclicBarrier forbidden.
 */
class ConcurrentLeaderElectionTest : AbstractLeaderElectionTest() {

    @Test
    fun `exactly one instance wins the lock among 3 concurrent attempts`() {
        val lockName = "test:t2:${UUID.randomUUID()}"
        val executions = AtomicInteger(0)   // java.util.concurrent.atomic — local variable
        val attemptCount = AtomicInteger(0)

        val shortOptions = LeaderElectionOptions(
            waitTime = 100.milliseconds,
            leaseTime = 5.seconds,
        )

        MultithreadingTester()
            .workers(3)
            .rounds(1)
            .add {
                val elector = newElector(shortOptions)
                attemptCount.incrementAndGet()
                elector.runIfLeader(lockName) {
                    executions.incrementAndGet()
                    // Hold the lock long enough for the other 2 workers to attempt (waitTime=100ms)
                    // and give up. Without this sleep, the winner releases instantly and another
                    // worker can acquire the lock before its waitTime expires.
                    Thread.sleep(500)
                }
            }
            .run()

        attemptCount.get() shouldBeEqualTo 3
        executions.get() shouldBeEqualTo 1
    }
}
