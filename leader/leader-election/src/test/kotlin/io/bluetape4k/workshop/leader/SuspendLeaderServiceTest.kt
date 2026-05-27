package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.workshop.leader.service.SuspendLeaderService
import kotlinx.coroutines.delay
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test
import io.bluetape4k.codec.Base58
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [SuspendLeaderService] and [io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElector].
 *
 * ## Key behaviours verified
 * - Single coroutine acquires leadership and executes the suspend action.
 * - Second elector receives `null` when the lock is held by the first elector.
 * - [SuspendedJobTester] concurrency: exactly one worker wins per lock round.
 */
class SuspendLeaderServiceTest : AbstractLeaderElectionTest() {

    @Test
    fun `single coroutine acquires leadership and executes action`() = runSuspendIO {
        val elector = newSuspendElector()
        val service = SuspendLeaderService(elector)

        val result = service.runLeaderWork()

        result.shouldNotBeNull() shouldBeEqualTo "done"
        service.executionCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `second elector receives null while first holds the lock`() = runSuspendIO {
        val lockName = "test:suspend:contention:${Base58.randomString(8)}"
        val shortWait = LeaderElectionOptions(waitTime = 50.milliseconds, leaseTime = 5.seconds)
        val elector1 = newSuspendElector(shortWait)
        val elector2 = newSuspendElector(shortWait)

        // Elector2 tries while elector1 holds the lock — elector2 times out (waitTime=50ms).
        var result2: String? = "not-set"
        val result1 = elector1.runIfLeader(lockName) {
            result2 = elector2.runIfLeader(lockName) { "follower" }
            "leader"
        }

        result1.shouldNotBeNull() shouldBeEqualTo "leader"
        result2.shouldBeNull()
    }

    @Test
    fun `exactly one coroutine wins among concurrent suspend attempts`() = runSuspendIO {
        val lockName = "test:suspend:concurrent:${Base58.randomString(8)}"
        val winCount = AtomicInteger(0)

        // SuspendedJobTester — coroutine concurrency harness from bluetape4k-junit5.
        // waitTime = 50ms so non-winners time out while the winner holds for 200ms.
        val opts = LeaderElectionOptions(waitTime = 50.milliseconds, leaseTime = 5.seconds)

        SuspendedJobTester()
            .workers(4)
            .rounds(1)
            .add {
                val elector = newSuspendElector(opts)
                elector.runIfLeader(lockName) {
                    winCount.incrementAndGet()
                    delay(300) // hold longer than waitTime so others time out
                }
            }
            .run()

        winCount.get() shouldBeEqualTo 1
    }
}
