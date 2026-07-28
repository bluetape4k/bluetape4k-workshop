package io.bluetape4k.workshop.leader.zookeeper

import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * T2: 동시 블로킹 리더 선출 테스트이다. 작업 본문 안에는 최대 worker 하나만 들어가야 한다.
 *
 * 8개 worker가 [AbstractLeaderZookeeperTest]의 공유 curator를 사용해
 * 하나의 공유 [io.bluetape4k.leader.zookeeper.ZooKeeperLeaderElector]를 두고 경쟁한다.
 *
 * ## 검증하는 동작
 * - 작업 본문 안에 동시에 들어온 worker 수의 최댓값인 `peakConcurrent`는 절대 1을 넘지 않는다.
 *   즉 lock이 엄격한 상호 배제를 제공한다.
 * - 8개 worker 중 최소 3개가 결국 작업을 성공적으로 실행한다. 이는 worker 사이에서 lock이 해제되고,
 *   대기 구간 안에 여러 번의 획득이 가능함을 증명한다.
 *
 * [MultithreadingTester](bluetape4k-junit5)를 사용한다. 직접 Thread/Executors/CyclicBarrier를 쓰지 않는다.
 */
class ConcurrentBlockingLeaderTest : AbstractLeaderZookeeperTest() {

    @Test
    fun `peakConcurrent never exceeds 1 across 8 concurrent workers`() {
        val lockName = randomLockName("t2")
        // worker들이 같은 대상에서 경쟁하도록 공유 단일 elector를 worker 블록 밖에서 한 번만 만든다.
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
                        // 다른 worker가 실제로 경합하도록 lock을 잠깐 유지하되,
                        // 500ms waitTime 안에 여러 worker가 순차 진입할 수 있을 만큼 짧게 유지한다.
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
