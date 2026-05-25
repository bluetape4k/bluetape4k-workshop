package io.bluetape4k.workshop.leader.zookeeper.service

import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.leader.zookeeper.config.LeaderZookeeperProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

/**
 * Blocking single-leader workshop service backed by [ZooKeeperLeaderElector].
 *
 * ## Behavior / Contract
 * - [runLeaderWork] returns the action result when this instance is elected, or `null` when skipped.
 * - The `@Scheduled` entry point logs and swallows non-cancellation exceptions to avoid
 *   killing the Spring scheduler thread.
 * - [executionCount] is exposed for testing; not for production use.
 */
@Service
class BlockingLeaderService(
    private val elector: ZooKeeperLeaderElector,
    @Suppress("unused") private val props: LeaderZookeeperProperties,
) {
    companion object : KLogging() {
        const val LOCK_NAME = "workshop:blocking-job"
    }

    /** Number of times this instance was elected and executed the leader action. */
    val executionCount = AtomicInteger(0)

    /**
     * Acquires leadership for [lockName] and executes the work body if elected.
     *
     * @return `"done"` when this instance won the lock, `null` when skipped.
     */
    fun runLeaderWork(lockName: String = LOCK_NAME): String? =
        elector.runIfLeader(lockName) {
            executionCount.incrementAndGet()
            log.info { "[LEADER] BlockingLeaderService running work (total=${executionCount.get()})" }
            "done"
        }.also {
            if (it == null) log.debug { "[SKIPPED] BlockingLeaderService not the elected leader" }
        }

    /**
     * Scheduled entry point. Catches non-cancellation exceptions so the Spring
     * scheduler thread remains healthy across invocations.
     */
    @Scheduled(fixedDelayString = "\${leader.zookeeper.job-fixed-delay:PT10S}")
    fun runScheduled() {
        try {
            runLeaderWork()
        } catch (e: Exception) {
            log.warn(e) { "Scheduled leader work failed" }
        }
    }
}
