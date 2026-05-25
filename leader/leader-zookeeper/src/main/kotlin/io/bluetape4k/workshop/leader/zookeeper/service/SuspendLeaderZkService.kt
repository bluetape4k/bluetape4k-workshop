package io.bluetape4k.workshop.leader.zookeeper.service

import io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.leader.zookeeper.config.LeaderZookeeperProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coroutine-native single-leader workshop service backed by [ZooKeeperSuspendLeaderElector].
 *
 * ## Behavior / Contract
 * - [runLeaderWork] suspends while waiting for the ZooKeeper lock; no thread is blocked.
 * - Returns the action result when elected, `null` when skipped.
 * - The `@Scheduled` entry point bridges Spring's blocking scheduler thread into a coroutine
 *   via `runBlocking { ... }` and **MUST** rethrow [CancellationException] so cooperative
 *   cancellation propagates to the scheduler.
 * - [executionCount] is exposed for testing; not for production use.
 */
@Service
class SuspendLeaderZkService(
    private val elector: ZooKeeperSuspendLeaderElector,
    @Suppress("unused") private val props: LeaderZookeeperProperties,
) {
    companion object : KLoggingChannel() {
        const val LOCK_NAME = "workshop:suspend-job"
    }

    /** Number of times this instance was elected and executed the leader action. */
    val executionCount = AtomicInteger(0)

    /**
     * Suspending leader work — safe to call from `runTest { ... }` in tests.
     *
     * @return `"done"` when this instance won the lock, `null` when skipped.
     */
    suspend fun runLeaderWork(lockName: String = LOCK_NAME): String? =
        elector.runIfLeader(lockName) {
            executionCount.incrementAndGet()
            log.info { "[LEADER] SuspendLeaderZkService coroutine work started" }
            delay(20) // simulate async I/O without blocking a thread
            log.info { "[LEADER] SuspendLeaderZkService coroutine work complete (total=${executionCount.get()})" }
            "done"
        }.also {
            if (it == null) log.debug { "[SKIPPED] SuspendLeaderZkService not the elected leader" }
        }

    /**
     * Scheduled entry point.
     *
     * Rethrows [CancellationException] (CLAUDE.md: never swallow cancellation) and
     * logs other exceptions so the scheduler thread survives transient failures.
     */
    @Scheduled(fixedDelayString = "\${leader.zookeeper.suspend-job-fixed-delay:PT12S}")
    fun runScheduled() {
        try {
            runBlocking { runLeaderWork() }
        } catch (e: CancellationException) {
            throw e // MUST rethrow — CLAUDE.md: never swallow CancellationException
        } catch (e: Exception) {
            log.warn(e) { "Scheduled suspend leader work failed" }
        }
    }
}
