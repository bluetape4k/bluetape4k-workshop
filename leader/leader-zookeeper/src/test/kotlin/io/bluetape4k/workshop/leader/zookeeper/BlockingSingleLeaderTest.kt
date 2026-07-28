package io.bluetape4k.workshop.leader.zookeeper

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderElector
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds

/**
 * T1: 블로킹 [ZooKeeperLeaderElector]의 단일 인스턴스 리더 선출 테스트이다.
 *
 * ## 검증하는 동작
 * - 단일 인스턴스가 lock을 획득하면 `runIfLeader`가 작업 결과를 반환한다.
 * - `runAsyncIfLeader`는 작업 결과와 같은 값을 담은 [CompletableFuture]를 반환한다.
 * - 첫 번째 elector가 lock을 보유 중이라 두 번째 elector가 자신의 `waitTime` 안에 획득하지 못하면
 *   `runIfLeader`는 `null`을 반환한다.
 */
class BlockingSingleLeaderTest : AbstractLeaderZookeeperTest() {

    @Test
    fun `runIfLeader returns done when a single elector acquires the lock`() {
        val elector = newElector()

        val result = elector.runIfLeader(randomLockName()) { "done" }

        result.shouldNotBeNull() shouldBeEqualTo "done"
    }

    @Test
    fun `runAsyncIfLeader returns 42 via the CompletableFuture`() {
        val elector = newElector()
        val executor = VirtualThreadExecutor

        val future: CompletableFuture<Int?> = elector.runAsyncIfLeader(
            lockName = randomLockName(),
            executor = executor,
        ) {
            CompletableFuture.completedFuture(42)
        }

        future.join().shouldNotBeNull() shouldBeEqualTo 42
    }

    @Test
    fun `runIfLeader returns null when the lock is held by another elector`() {
        val lockName = randomLockName()
        // 서로 다른 두 elector가 같은 lock을 두고 경쟁한다.
        // elector1은 메인 테스트 스레드에서 lock을 보유하고, elector2는 waitTime = 0ms를 사용한다.
        // InterProcessMutex 소유권은 Thread.currentThread() 기준이므로, elector2는 반드시
        // 다른 스레드에서 실행해야 한다. 그렇지 않으면 재진입으로 lock을 획득한다.
        val elector1: ZooKeeperLeaderElector = newElector()
        val elector2 = ZooKeeperLeaderElector(
            curator,
            "/test/single",
            LeaderElectionOptions(waitTime = 0.milliseconds)
        )

        val result2Ref = java.util.concurrent.atomic.AtomicReference<String?>("not-set")
        val result1 = elector1.runIfLeader(lockName) {
            // elector2가 elector1의 lock을 재진입으로 소유하지 않도록 별도 스레드에서 실행한다.
            val follower = Thread {
                result2Ref.set(elector2.runIfLeader(lockName) { "follower" })
            }
            follower.start()
            follower.join()
            "leader"
        }

        result1.shouldNotBeNull() shouldBeEqualTo "leader"
        result2Ref.get().shouldBeNull()
    }
}
