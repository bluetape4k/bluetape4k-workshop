package io.bluetape4k.workshop.leader.zookeeper.service

import io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderGroupElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.leader.zookeeper.config.LeaderZookeeperProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coroutine-native group-leader workshop service backed by [ZooKeeperSuspendLeaderGroupElector].
 *
 * ## Behavior / Contract
 * - Up to `maxLeaders` instances may simultaneously execute the action body.
 * - [runLeaderWork] suspends while waiting for an available slot; no thread is blocked.
 * - Returns the action result when admitted, `null` when no slot was available within wait time.
 * - The `@Scheduled` entry point bridges Spring's blocking scheduler thread into a coroutine
 *   via `runBlocking { ... }` and **MUST** rethrow [CancellationException] so cooperative
 *   cancellation propagates to the scheduler.
 * - [executionCount] is exposed for testing; not for production use.
 */
@Service
class SuspendGroupLeaderService(
    private val elector: ZooKeeperSuspendLeaderGroupElector,
    @Suppress("unused") private val props: LeaderZookeeperProperties,
) {
    companion object : KLoggingChannel() {
        const val LOCK_NAME = "workshop:suspend-group-job"
    }

    /** Number of times this instance entered a leader slot. */
    val executionCount = AtomicInteger(0)

    /**
     * Suspending group-leader work — safe to call from `runTest { ... }` in tests.
     *
     * @return `"done"` when this instance entered a slot, `null` when no slot was available.
     */
    suspend fun runLeaderWork(lockName: String = LOCK_NAME): String? {
        lockName.requireNotBlank("lockName")
        return elector.runIfLeader(lockName) {
            executionCount.incrementAndGet()
            log.info { "[GROUP-LEADER] SuspendGroupLeaderService entered slot" }
            delay(20) // simulate async I/O without blocking a thread
            log.info { "[GROUP-LEADER] SuspendGroupLeaderService slot work complete (total=${executionCount.get()})" }
            "done"
        }.also {
            if (it == null) log.debug { "[SKIPPED] SuspendGroupLeaderService — no slot available" }
        }
    }

    /**
     * Scheduled entry point.
     *
     * Rethrows [CancellationException] (CLAUDE.md: never swallow cancellation) and
     * logs other exceptions so the scheduler thread survives transient failures.
     */
    @Scheduled(fixedDelayString = "\${leader.zookeeper.suspend-group-job-fixed-delay:PT18S}")
    fun runScheduled() {
        try {
            runBlocking { runLeaderWork() }
        } catch (e: CancellationException) {
            throw e // MUST rethrow — CLAUDE.md: never swallow CancellationException
        } catch (e: Exception) {
            log.warn(e) { "Scheduled suspend group leader work failed" }
        }
    }
}
