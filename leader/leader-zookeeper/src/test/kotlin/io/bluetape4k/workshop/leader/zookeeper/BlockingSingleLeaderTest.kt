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
 * T1: Single-instance leader election test for the blocking [ZooKeeperLeaderElector].
 *
 * ## Behavior verified
 * - `runIfLeader` returns the action result when a single instance acquires the lock.
 * - `runAsyncIfLeader` returns a [CompletableFuture] whose value matches the action result.
 * - `runIfLeader` returns `null` when a second elector cannot acquire the lock within
 *   its `waitTime` because the first elector is holding it.
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
        // Two distinct electors competing for the same lock.
        // elector1 (on the main test thread) holds the lock; elector2 has waitTime = 0ms.
        // InterProcessMutex ownership is keyed on Thread.currentThread(), so elector2
        // MUST run on a different thread or it would be granted the lock reentrantly.
        val elector1: ZooKeeperLeaderElector = newElector()
        val elector2 = ZooKeeperLeaderElector(
            curator,
            "/test/single",
            LeaderElectionOptions(waitTime = 0.milliseconds)
        )

        val result2Ref = java.util.concurrent.atomic.AtomicReference<String?>("not-set")
        val result1 = elector1.runIfLeader(lockName) {
            // Run elector2 on a separate thread so it does NOT reentrantly own elector1's lock.
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
