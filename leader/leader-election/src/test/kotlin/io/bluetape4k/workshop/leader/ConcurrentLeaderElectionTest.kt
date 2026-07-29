package io.bluetape4k.workshop.leader

import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import io.bluetape4k.codec.Base58
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * T2: Concurrent leader election - N개 instance 중 정확히 하나만 winner가 됩니다.
 *
 * 각자 Lettuce connection과 [LettuceLeaderElector]를 가진 worker 3개를 병렬 실행합니다.
 * 다음을 검증합니다.
 * 1. worker 3개 모두 `runIfLeader`를 시도했습니다(attempt count = 3).
 * 2. 정확히 worker 1개만 action body를 실행했습니다(execution count = 1).
 *
 * [MultithreadingTester](bluetape4k-junit5)를 사용합니다. raw Thread/Executors/CyclicBarrier는 금지합니다.
 */
class ConcurrentLeaderElectionTest : AbstractLeaderElectionTest() {

    @Test
    fun `exactly one instance wins the lock among 3 concurrent attempts`() {
        val lockName = "test:t2:${Base58.randomString(8)}"
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
                    // 다른 worker 2개가 시도하고 포기할 만큼(waitTime=100ms) lock을 유지합니다.
                    // 이 sleep이 없으면 winner가 즉시 release하고 다른 worker가 waitTime 만료 전에 lock을 획득할 수 있습니다.
                    Thread.sleep(500)
                }
            }
            .run()

        attemptCount.get() shouldBeEqualTo 3
        executions.get() shouldBeEqualTo 1
    }
}
