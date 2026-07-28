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
 * T3: [io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderElector]의
 * 단일 코루틴 및 동시 suspend 리더 선출 테스트이다.
 *
 * ## 검증하는 동작
 * - 단일 suspending 호출자가 리더십을 획득하고 작업이 `"done"`을 반환한다.
 * - 8개 코루틴이 [SuspendedJobTester]와 공유 elector를 통해 경쟁할 때
 *   `peakConcurrent`는 1을 넘지 않으며(엄격한 상호 배제), 최소 3개 코루틴이 결국 작업 본문을 실행한다.
 *
 * 참고: 동시성 테스트는 `runTest`가 아니라 `runSuspendIO`를 사용한다. 실제 ZooKeeper 네트워크 I/O는
 * `runTest`가 사용하는 테스트 가상 시간 scheduler와 협력하지 않기 때문이다.
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
        // worker들이 같은 대상에서 경쟁하도록 공유 단일 suspend elector를 worker 블록 밖에서 한 번만 만든다.
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
                        // worker들이 경합하면서도 여러 개가 순차 진입할 수 있도록 잠깐 유지한다.
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
