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
 * [SuspendLeaderService]와 [io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElector]의 테스트입니다.
 *
 * ## 검증하는 주요 동작
 * - 단일 coroutine이 leadership을 획득하고 suspend action을 실행합니다.
 * - 첫 번째 elector가 lock을 보유 중이면 두 번째 elector는 `null`을 받습니다.
 * - [SuspendedJobTester] concurrency에서 lock round마다 정확히 worker 하나만 승리합니다.
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

        // elector1이 lock을 보유하는 동안 elector2가 시도합니다. elector2는 timeout됩니다(waitTime=50ms).
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

        // SuspendedJobTester는 bluetape4k-junit5의 coroutine concurrency harness입니다.
        // winner가 200ms 동안 보유하는 동안 non-winner가 timeout되도록 waitTime = 50ms로 둡니다.
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
