package io.bluetape4k.workshop.leader.zookeeper

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.zookeeper.runAsyncIfLeader
import io.bluetape4k.leader.zookeeper.runIfLeader
import io.bluetape4k.leader.zookeeper.runIfLeaderGroup
import io.bluetape4k.leader.zookeeper.suspendRunIfLeader
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds

/**
 * T6: `io.bluetape4k.leader.zookeeper` 패키지가 제공하는
 * [org.apache.curator.framework.CuratorFramework] 확장 함수 테스트이다.
 *
 * ## 검증하는 동작
 * - `runIfLeader` 확장 함수는 리더십을 획득하고 작업 결과를 반환한다.
 * - `runAsyncIfLeader` 확장 함수는 작업 결과와 같은 값을 담은 [CompletableFuture]를 반환한다.
 * - `suspendRunIfLeader` 확장 함수는 suspend 작업 결과를 반환한다.
 * - `runIfLeaderGroup` 확장 함수는 그룹 slot을 획득하고 작업 결과를 반환한다.
 *   파라미터 이름은 라이브러리 소스에서 확인한 대로 `groupOptions=`가 아니라 `options=`이다.
 *
 * 각 확장 함수 호출은 이 테스트 묶음의 다른 테스트와 ZooKeeper 경로가 충돌하지 않도록
 * 고유한 `basePath`를 사용한다.
 */
class ExtensionFunctionTest : AbstractLeaderZookeeperTest() {

    private val singleOptions = LeaderElectionOptions(waitTime = 500.milliseconds)

    @Test
    fun `runIfLeader extension returns the action result`() {
        val result = curator.runIfLeader(
            lockName = randomLockName("t6-run"),
            basePath = "/test/ext-single",
            options = singleOptions,
        ) {
            "done"
        }

        result.shouldNotBeNull() shouldBeEqualTo "done"
    }

    @Test
    fun `runAsyncIfLeader extension returns the action result via CompletableFuture`() {
        val future: CompletableFuture<String?> = curator.runAsyncIfLeader(
            lockName = randomLockName("t6-async"),
            executor = VirtualThreadExecutor,
            basePath = "/test/ext-async",
            options = singleOptions,
        ) {
            CompletableFuture.completedFuture("async-done")
        }

        future.join().shouldNotBeNull() shouldBeEqualTo "async-done"
    }

    @Test
    fun `suspendRunIfLeader extension returns the suspending action result`(): Unit = runSuspendIO {
        val result = curator.suspendRunIfLeader(
            lockName = randomLockName("t6-suspend"),
            basePath = "/test/ext-suspend",
            options = singleOptions,
        ) {
            "suspend-done"
        }

        result.shouldNotBeNull() shouldBeEqualTo "suspend-done"
    }

    @Test
    fun `runIfLeaderGroup extension acquires a slot and returns the action result`() {
        val result = curator.runIfLeaderGroup(
            lockName = randomLockName("t6-group"),
            options = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 500.milliseconds),
            basePath = "/test/ext-group",
        ) {
            "done"
        }

        result.shouldNotBeNull() shouldBeEqualTo "done"
    }
}
