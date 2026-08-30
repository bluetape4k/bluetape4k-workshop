package io.bluetape4k.workshop.leader.jobsafety.coordination.redis

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.workshop.leader.jobsafety.coordination.LeaderElectionPort
import io.bluetape4k.workshop.leader.jobsafety.coordination.LeaderLease
import io.bluetape4k.workshop.leader.jobsafety.domain.JobName
import io.bluetape4k.workshop.leader.jobsafety.domain.LeaderOwnerId
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * blocking elector를 job-safety 요청 lease로 연결합니다.
 *
 * backend가 보유한 `LeaderLockHandle`은 owner executor thread의 scope에만 존재합니다.
 * 따라서 요청 thread에서 raw handle을 복사하지 않고 bounded command queue를 통해 owner
 * thread의 실제 [LockExtender]를 호출합니다.
 */
class RedisLeaderElectionAdapter(
    private val backend: LeaderElector,
    private val executor: Executor,
    private val ownerIds: () -> LeaderOwnerId = { LeaderOwnerId(Uuid.V7.nextId().toString()) },
) : LeaderElectionPort {
    override fun tryAcquire(jobName: JobName): LeaderLease? {
        val ownerId = ownerIds()
        val entered = CompletableFuture<Unit>()
        val completed = CompletableFuture<Unit>()
        val session = SyncSession()
        val lockName = "job-safety:${jobName.value}"

        executor.execute {
            try {
                var elected = false
                backend.runIfLeader(LeaderSlot(lockName, ownerId.value)) {
                    elected = true
                    entered.complete(Unit)
                    session.awaitCommands()
                }
                if (!elected) {
                    entered.completeExceptionally(LeaderNotAcquired)
                } else {
                    completed.complete(Unit)
                }
            } catch (failure: Throwable) {
                if (!entered.isDone) entered.completeExceptionally(failure)
                completed.completeExceptionally(failure)
            } finally {
                session.rejectPending()
            }
        }

        return try {
            entered.get()
            RedisLeaderLease(ownerId, session, completed)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            session.cancel()
            throw failure
        } catch (failure: ExecutionException) {
            session.cancel()
            when (val cause = failure.cause ?: failure) {
                LeaderNotAcquired -> null
                is RuntimeException -> throw cause
                else -> throw IllegalStateException("leader election failed", cause)
            }
        }
    }

    private class SyncSession {
        private sealed interface Command {
            data class ExtendViaLockExtender(
                val duration: Duration,
                val result: CompletableFuture<ExtendOutcome>,
            ) : Command

            data object Release : Command
        }

        private val commands = ArrayBlockingQueue<Command>(COMMAND_CAPACITY)
        private val released = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)

        fun awaitCommands() {
            while (true) {
                val command = try {
                    commands.poll(COMMAND_POLL_INTERVAL.inWholeNanoseconds, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    cancel()
                    return
                }

                if (command == null) {
                    if (released.get() || cancelled.get()) return
                    continue
                }

                when (command) {
                    is Command.ExtendViaLockExtender -> {
                        if (command.result.isDone) {
                            continue
                        }
                        if (released.get() || cancelled.get()) {
                            command.result.complete(ExtendOutcome.NotHeld)
                        } else {
                            command.result.complete(runCatching {
                                LockExtender.extendActiveLockDetailed(command.duration)
                            }.getOrElse { failure ->
                                when (failure) {
                                    is java.util.concurrent.CancellationException -> throw failure
                                    is Exception -> ExtendOutcome.BackendError(failure)
                                    else -> throw failure
                                }
                            })
                        }
                    }

                    Command.Release -> {
                        released.set(true)
                        rejectPending()
                        return
                    }
                }
            }
        }

        fun extendViaLockExtender(lockAtMostFor: Duration): ExtendOutcome {
            if (released.get() || cancelled.get()) return ExtendOutcome.NotHeld
            val result = CompletableFuture<ExtendOutcome>()
            if (!commands.offer(
                    Command.ExtendViaLockExtender(
                        duration = lockAtMostFor,
                        result = result,
                    ),
                )
            ) {
                return ExtendOutcome.Rejected
            }

            return try {
                result.get(responseTimeout(lockAtMostFor), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                result.complete(ExtendOutcome.Rejected)
                ExtendOutcome.Rejected
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                result.complete(ExtendOutcome.Rejected)
                ExtendOutcome.Rejected
            } catch (failure: ExecutionException) {
                val cause = failure.cause ?: failure
                if (cause is Exception) ExtendOutcome.BackendError(cause) else throw cause
            }
        }

        fun release() {
            if (!released.compareAndSet(false, true)) return
            commands.offer(Command.Release)
            rejectPending()
        }

        fun cancel() {
            cancelled.set(true)
            released.set(true)
            rejectPending()
        }

        fun rejectPending() {
            while (true) {
                when (val pending = commands.poll() ?: return) {
                    is Command.ExtendViaLockExtender -> pending.result.complete(ExtendOutcome.Rejected)
                    Command.Release -> Unit
                }
            }
        }

        private fun responseTimeout(duration: Duration): Long =
            minOf(duration.coerceAtLeast(MIN_RESPONSE_TIMEOUT), MAX_RESPONSE_TIMEOUT)
                .inWholeNanoseconds
                .coerceAtLeast(1L)

        private companion object {
            private const val COMMAND_CAPACITY = 32
            private val COMMAND_POLL_INTERVAL = 25.milliseconds
            private val MIN_RESPONSE_TIMEOUT = 1.milliseconds
            private val MAX_RESPONSE_TIMEOUT = 1.seconds
        }
    }

    private class RedisLeaderLease(
        override val ownerId: LeaderOwnerId,
        private val session: SyncSession,
        private val completed: CompletableFuture<Unit>,
    ) : LeaderLease {
        override fun extendViaLockExtender(lockAtMostFor: Duration): ExtendOutcome =
            session.extendViaLockExtender(lockAtMostFor)

        override fun release() {
            session.release()
            try {
                completed.get(RELEASE_TIMEOUT.inWholeNanoseconds, TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                throw IllegalStateException("leader lease release timed out")
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            } catch (failure: ExecutionException) {
                val cause = failure.cause ?: failure
                if (cause is RuntimeException) throw cause
                throw IllegalStateException("leader lease completion failed", cause)
            }
        }

        private companion object {
            private val RELEASE_TIMEOUT = 5.seconds
        }
    }

    private data object LeaderNotAcquired : RuntimeException("leader_not_acquired")
}
