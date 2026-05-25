package io.bluetape4k.workshop.leader.service

import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import kotlinx.coroutines.delay
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

/**
 * Leader election service demonstrating coroutine-first leadership using [LettuceSuspendLeaderElector].
 *
 * In multi-instance deployments, `suspend` leader jobs eliminate blocking while waiting
 * for lock acquisition. Only the elected leader instance executes the work body;
 * all others receive `null` from `runIfLeader` and skip silently.
 *
 * ## Behavior / Contract
 * - [runIfLeader] returns `null` when this instance did not win the lock (skipped).
 * - [runIfLeader] suspends the caller during lock-acquisition wait without blocking a thread.
 * - The `@Scheduled` method wraps the coroutine call with `kotlinx.coroutines.runBlocking`
 *   at the Spring scheduler boundary (blocking is acceptable here because the scheduler
 *   thread is dedicated to this invocation).
 * - [executionCount] is exposed for testing; not for production use.
 */
@Service
class SuspendLeaderService(
    private val suspendLeaderElector: LettuceSuspendLeaderElector,
) {
    /** Number of times this instance was elected and executed the leader action. */
    val executionCount = AtomicInteger(0)

    companion object : KLogging() {
        const val LOCK_NAME = "leader:suspend-demo"
    }

    /**
     * Executes a coroutine leader action.
     *
     * Returns the action result when this instance is elected, or `null` when skipped.
     * Suitable for direct use in tests with `runTest {}`.
     */
    suspend fun runLeaderWork(): String? =
        suspendLeaderElector.runIfLeader(LOCK_NAME) {
            log.info { "[SuspendLeaderService] Coroutine leader work started" }
            delay(20) // simulate async I/O without blocking a thread
            executionCount.incrementAndGet()
            log.info { "[SuspendLeaderService] Coroutine leader work complete (total=${executionCount.get()})" }
            "done"
        }

    /**
     * Scheduled entry point — bridges the blocking Spring scheduler thread into a coroutine.
     *
     * Uses `kotlinx.coroutines.runBlocking` at the scheduler boundary only.
     * All actual leader work runs as a suspend function via [runLeaderWork].
     */
    @Scheduled(fixedDelayString = "\${leader.suspend-job-fixed-delay:PT15S}")
    fun runScheduled() {
        log.debug { "[SuspendLeaderService] Scheduled trigger — entering coroutine" }
        kotlinx.coroutines.runBlocking {
            val result = runLeaderWork()
            if (result != null) {
                log.info { "[SuspendLeaderService] [LEADER] Scheduled coroutine job executed: result=$result" }
            } else {
                log.debug { "[SuspendLeaderService] [SKIPPED] Not the elected leader this round" }
            }
        }
    }
}
