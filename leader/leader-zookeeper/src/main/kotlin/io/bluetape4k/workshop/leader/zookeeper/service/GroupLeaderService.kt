package io.bluetape4k.workshop.leader.zookeeper.service

import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderGroupElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.leader.zookeeper.config.LeaderZookeeperProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

/**
 * Blocking group-leader workshop service backed by [ZooKeeperLeaderGroupElector].
 *
 * ## Behavior / Contract
 * - Up to `maxLeaders` instances may simultaneously execute the action body.
 * - [runLeaderWork] returns the action result when this instance entered a slot, or `null`
 *   when all slots were taken within the wait time.
 * - [inspectState] returns the current [LeaderGroupState] for a lock — useful in tests
 *   to verify `activeCount` and `availableSlots`.
 * - [executionCount] is exposed for testing; not for production use.
 */
@Service
class GroupLeaderService(
    private val elector: ZooKeeperLeaderGroupElector,
    @Suppress("unused") private val props: LeaderZookeeperProperties,
) {
    companion object : KLogging() {
        const val LOCK_NAME = "workshop:group-job"
    }

    /** Number of times this instance entered a leader slot. */
    val executionCount = AtomicInteger(0)

    /**
     * Attempts to enter a leader slot for [lockName] and executes the work body if admitted.
     *
     * @return `"done"` when this instance entered a slot, `null` when no slot was available.
     */
    fun runLeaderWork(lockName: String = LOCK_NAME): String? {
        lockName.requireNotBlank("lockName")
        return elector.runIfLeader(lockName) {
            executionCount.incrementAndGet()
            log.info { "[GROUP-LEADER] GroupLeaderService entered slot (total=${executionCount.get()})" }
            "done"
        }.also {
            if (it == null) log.debug { "[SKIPPED] GroupLeaderService — no slot available" }
        }
    }

    /**
     * Inspects the current group-election state for [lockName].
     *
     * Surfaces `activeCount` and `availableSlots` for tests and operational dashboards.
     */
    fun inspectState(lockName: String = LOCK_NAME): LeaderGroupState {
        lockName.requireNotBlank("lockName")
        return elector.state(lockName)
    }

    /**
     * Scheduled entry point. Catches non-cancellation exceptions so the Spring
     * scheduler thread remains healthy across invocations.
     */
    @Scheduled(fixedDelayString = "\${leader.zookeeper.group-job-fixed-delay:PT15S}")
    fun runScheduled() {
        try {
            runLeaderWork()
        } catch (e: Exception) {
            log.warn(e) { "Scheduled group leader work failed" }
        }
    }
}
