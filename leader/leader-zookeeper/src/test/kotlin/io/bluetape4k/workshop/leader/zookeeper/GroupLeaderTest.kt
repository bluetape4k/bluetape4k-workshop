package io.bluetape4k.workshop.leader.zookeeper

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * T4 - 동시 경합 상황에서 ZooKeeper 그룹 리더 elector가 동시에 정확히 [maxLeaders]개 holder만
 * 허용하는지 검증한다.
 *
 * ## 동작 / 계약
 * - `CountDownLatch(maxLeaders)` 핸드셰이크로 작업 본문 *내부*의 최대 동시 실행 수를 단언한다.
 *   `MultithreadingTester.run()`이 반환된 뒤 샘플링하면 이미 모든 holder가 해제된 뒤라 너무 늦다.
 * - orchestrator 스레드는 블로킹 호출인 `MultithreadingTester.run()` **전에** 반드시 시작해야 한다.
 *   `run()` 뒤에 시작하면 `run()`은 모든 worker가 끝난 뒤에야 반환되므로,
 *   worker가 `releaseLatch.await`에서 영원히 막히는 전형적인 deadlock이 발생한다.
 * - `peakConcurrent`는 `AtomicInteger.updateAndGet(max(...))`로 갱신해 여러 worker 스레드의 최댓값을 안전하게 샘플링한다.
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

        // 중요: orchestrator는 블로킹 호출인 MultithreadingTester.run() 전에 반드시 시작해야 한다.
        // 그렇지 않으면 worker가 releaseLatch.await에서 영원히 막혀 deadlock이 발생한다.
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
