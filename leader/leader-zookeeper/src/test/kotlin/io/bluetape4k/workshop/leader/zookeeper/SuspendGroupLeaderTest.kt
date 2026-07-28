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
 * T5 - [GroupLeaderTest]의 suspend 변형이다.
 *
 * [io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderGroupElector]가 동시에 정확히
 * `maxLeaders`개 coroutine holder만 허용하는지 검증한다.
 *
 * ## 동작 / 계약
 * - T4와 같은 CountDownLatch 핸드셰이크를 사용하되, worker는 [SuspendedJobTester]를 통해 코루틴으로 실행한다.
 * - 호출 스레드 관점에서 `SuspendedJobTester.run()`은 블로킹 호출이므로 orchestrator 스레드는
 *   `SuspendedJobTester.run()` **전에** 반드시 시작해야 한다. suspend builder는 `runSuspendIO`로 감싼다.
 * - `CountDownLatch`는 구조적 suspension을 요구하지 않고 결정적인 동기화 barrier를 제공하므로 코루틴 안에서도 안전하게 사용할 수 있다.
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

        // 중요: orchestrator는 SuspendedJobTester.run() 전에 반드시 시작해야 한다.
        // T4와 같은 deadlock 위험이 있다. 그렇지 않으면 worker가 releaseLatch.await에서 영원히 막힌다.
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
                        // CountDownLatch.await는 carrier thread를 블로킹하지만,
                        // 하위 코루틴이 전용 스레드로 dispatch되므로 이 작업 본문 안에서는 허용된다.
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
