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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds

/**
 * T6: [org.apache.curator.framework.CuratorFramework] extension functions exposed by the
 * `io.bluetape4k.leader.zookeeper` package.
 *
 * ## Behavior verified
 * - `runIfLeader` extension acquires leadership and returns the action result.
 * - `runAsyncIfLeader` extension returns a [CompletableFuture] whose value matches the action result.
 * - `suspendRunIfLeader` extension returns the suspending action result.
 * - `runIfLeaderGroup` extension acquires a group slot and returns the action result.
 *   The parameter is named `options=` (NOT `groupOptions=`), confirmed from the library source.
 *
 * Each extension call uses a unique `basePath` to avoid cross-test path collisions
 * with other tests in this suite.
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
    fun `suspendRunIfLeader extension returns the suspending action result`() = runTest {
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
