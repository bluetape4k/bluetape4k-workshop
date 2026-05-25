package io.bluetape4k.workshop.leader.job

import io.bluetape4k.leader.LockExtender
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Leader-guarded job that demonstrates runtime lease extension using [LockExtender].
 *
 * When a job body runs longer than the configured lease TTL, the leader lock can expire
 * before the job finishes, allowing another instance to acquire the lock and cause
 * split-brain execution. [LockExtender.extendActiveLock] prevents this by extending
 * the current lease from within the job body.
 *
 * ## Behavior / Contract
 * - Calls [LockExtender.extendActiveLock] after the first work phase to renew the lease.
 * - Returns `false` (extension failed) only when the lock is no longer held by this instance
 *   (e.g., another node already took over). The job logs a warning but continues, demonstrating
 *   the check pattern.
 * - This bean is registered as `@Component` so it is auto-injected into
 *   [LeaderScheduledJobService] via `List<LeaderGuardedJob>`.
 *
 * ## Usage
 * In production, pair a long-running job with `LockExtender.extendActiveLock()` calls
 * after each major phase, using the remaining estimated runtime as the extension delta.
 */
@Component
class LockExtenderJob : LeaderGuardedJob {

    override val lockName = "leader:lock-extender-demo"

    init {
        lockName.requireNotBlank("lockName")
    }

    override fun execute() {
        log.info { "[LockExtenderJob] Starting — simulating a long-running leader job" }

        // Phase 1: first unit of work
        Thread.sleep(50)
        log.debug { "[LockExtenderJob] Phase 1 complete" }

        // Extend lease before Phase 2 to avoid expiry mid-execution.
        // extendActiveLock uses the current thread's LeaderLockHandle captured by the aspect.
        val extended = LockExtender.extendActiveLock(EXTENSION_DURATION)
        if (extended) {
            log.info { "[LockExtenderJob] Lease extended by $EXTENSION_DURATION — continuing Phase 2" }
        } else {
            log.warn { "[LockExtenderJob] Lease extension failed (lock not held or lost) — proceeding anyway for demo" }
        }

        // Phase 2: second unit of work
        Thread.sleep(50)
        log.info { "[LockExtenderJob] Phase 2 complete — job finished" }
    }

    companion object : KLogging() {
        /** Extension duration added to the active lease between work phases. */
        val EXTENSION_DURATION: Duration = Duration.ofSeconds(30)
    }
}
