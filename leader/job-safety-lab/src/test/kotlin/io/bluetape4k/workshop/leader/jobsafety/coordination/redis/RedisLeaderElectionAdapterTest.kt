package io.bluetape4k.workshop.leader.jobsafety.coordination.redis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.workshop.leader.jobsafety.domain.JobName
import io.bluetape4k.workshop.leader.jobsafety.domain.LeaderOwnerId
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

internal class RedisLeaderElectionAdapterTest {
    @Test
    fun `opaque leader identity is never exposed as a fencing token`() {
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val adapter =
                RedisLeaderElectionAdapter(
                    backend = electedBackend(),
                    executor = executor,
                    ownerIds = { LeaderOwnerId("audit-owner") },
                )

            val lease = requireNotNull(adapter.tryAcquire(JobName("daily-summary")))

            lease.ownerId shouldBeEqualTo LeaderOwnerId("audit-owner")
            lease.javaClass.declaredFields.any { it.name.contains("fencing", ignoreCase = true) }.shouldBeFalse()
            lease.release()
        }
    }

    @Test
    fun `leadership remains held until the lease is released`() {
        val events = mutableListOf<String>()
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val backend =
                object : SyncLeaderElector() {
                    override fun <T> runIfLeader(lockName: String, action: () -> T): T {
                        events += "backend.acquired:$lockName"
                        val result = action()
                        events += "backend.released:$lockName"
                        return result
                    }
                }
            val adapter = RedisLeaderElectionAdapter(backend, executor) { LeaderOwnerId("owner-1") }

            val lease = requireNotNull(adapter.tryAcquire(JobName("daily-summary")))
            events shouldBeEqualTo listOf("backend.acquired:job-safety:daily-summary")

            lease.release()
            events shouldBeEqualTo
                listOf("backend.acquired:job-safety:daily-summary", "backend.released:job-safety:daily-summary")
        }
    }

    @Test
    fun `contention returns no lease`() {
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val backend =
                object : SyncLeaderElector() {
                    override fun <T> runIfLeader(lockName: String, action: () -> T): T? = null
                }
            val adapter = RedisLeaderElectionAdapter(backend, executor) { LeaderOwnerId("owner-1") }

            (adapter.tryAcquire(JobName("daily-summary")) == null).shouldBeTrue()
        }
    }

    @Test
    fun `backend completion failure is reported during release`() {
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val backend =
                object : SyncLeaderElector() {
                    override fun <T> runIfLeader(lockName: String, action: () -> T): T {
                        action()
                        error("auto extension failed")
                    }
                }
            val adapter = RedisLeaderElectionAdapter(backend, executor) { LeaderOwnerId("owner-1") }
            val lease = requireNotNull(adapter.tryAcquire(JobName("daily-summary")))

            assertFailsWith<IllegalStateException> { lease.release() }
        }
    }

    private fun electedBackend(): LeaderElector =
        object : SyncLeaderElector() {
            override fun <T> runIfLeader(lockName: String, action: () -> T): T = action()
        }

    private abstract class SyncLeaderElector : LeaderElector {
        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> =
            CompletableFuture.supplyAsync(
                { runIfLeader(lockName) { action().join() } },
                executor,
            )
    }
}
