package io.bluetape4k.workshop.leader.jobsafety.coordination.redis

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.workshop.leader.jobsafety.coordination.LeaderElectionPort
import io.bluetape4k.workshop.leader.jobsafety.coordination.LeaderLease
import io.bluetape4k.workshop.leader.jobsafety.domain.JobName
import io.bluetape4k.workshop.leader.jobsafety.domain.LeaderOwnerId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class RedisLeaderElectionAdapter(
    private val backend: LeaderElector,
    private val executor: Executor,
    private val ownerIds: () -> LeaderOwnerId = { LeaderOwnerId(Uuid.V7.nextId().toString()) },
) : LeaderElectionPort {
    override fun tryAcquire(jobName: JobName): LeaderLease? {
        val ownerId = ownerIds()
        val entered = CompletableFuture<Unit>()
        val completed = CompletableFuture<Unit>()
        val releaseSignal = CountDownLatch(1)
        val lockName = "job-safety:${jobName.value}"

        executor.execute {
            try {
                var elected = false
                backend.runIfLeader(LeaderSlot(lockName, ownerId.value)) {
                    elected = true
                    entered.complete(Unit)
                    releaseSignal.await()
                }
                if (!elected) entered.completeExceptionally(LeaderNotAcquired)
                completed.complete(Unit)
            } catch (e: Throwable) {
                entered.completeExceptionally(e)
                completed.completeExceptionally(e)
            }
        }

        return try {
            entered.get()
            RedisLeaderLease(ownerId, releaseSignal, completed)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: ExecutionException) {
            when (val cause = e.cause ?: e) {
                LeaderNotAcquired -> null
                is RuntimeException -> throw cause
                else -> throw IllegalStateException("leader election failed", cause)
            }
        }
    }

    private class RedisLeaderLease(
        override val ownerId: LeaderOwnerId,
        private val releaseSignal: CountDownLatch,
        private val completed: CompletableFuture<Unit>,
    ) : LeaderLease {
        private val released = AtomicBoolean()

        override fun release() {
            if (released.compareAndSet(false, true)) releaseSignal.countDown()
            try {
                completed.get()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                if (cause is RuntimeException) throw cause
                throw IllegalStateException("leader lease completion failed", cause)
            }
        }
    }

    private data object LeaderNotAcquired : RuntimeException("leader_not_acquired")
}
