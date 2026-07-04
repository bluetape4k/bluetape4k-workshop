package io.bluetape4k.workshop.leader.job

import io.bluetape4k.logging.*
import io.bluetape4k.support.requireNotBlank
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Example leader-guarded job: stale workflow cleanup.
 *
 * Simulates cleanup of timed-out or abandoned workflows. This job must run
 * on exactly one instance to avoid double-deletion in multi-instance deployments.
 *
 * ## Behavior / Contract
 * - [lockName] is validated non-blank in `init {}`.
 * - [execute] logs a cleanup message and simulates a short delay.
 * - This bean is registered as a Spring `@Component` so it is auto-discovered
 *   and injected into [LeaderScheduledJobService] via `List<LeaderGuardedJob>`.
 */
@Component
class StaleWorkflowCleanupJob : LeaderGuardedJob {

    override val lockName = "leader:stale-workflow-cleanup"

    init {
        lockName.requireNotBlank("lockName")
    }

    override fun execute() {
        log.info { "[StaleWorkflowCleanupJob] Scanning for stale workflows on elected leader instance" }
        // Simulate cleanup (e.g., mark timed-out workflow records as CANCELLED)
        simulateBlockingWork(CLEANUP_DURATION)
        log.info { "[StaleWorkflowCleanupJob] Stale workflow cleanup complete" }
    }

    companion object : KLogging() {
        private val CLEANUP_DURATION: Duration = Duration.ofMillis(50)
    }
}
