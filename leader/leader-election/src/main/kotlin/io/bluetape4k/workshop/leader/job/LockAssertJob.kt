package io.bluetape4k.workshop.leader.job

import io.bluetape4k.leader.LockAssert
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Leader-guarded job that demonstrates lock ownership verification using [LockAssert].
 *
 * [LockAssert.assertLocked] and [LockAssert.isLocked] let job code verify at runtime
 * that the distributed lock is still held by this instance. This is useful for:
 * - Early-exit guards before expensive operations.
 * - Defensive checks in helper methods that assume leader ownership.
 * - Integration tests verifying that AOP wiring propagates the lock handle correctly.
 *
 * ## Behavior / Contract
 * - [LockAssert.assertLocked] throws [IllegalStateException] when called outside of a
 *   `runIfLeader` context (i.e., lock handle not present on the current thread).
 * - [LockAssert.isLocked] returns `false` rather than throwing, and is safe to call
 *   anywhere as a guard condition.
 * - This bean is registered as `@Component` so it is auto-injected into
 *   [LeaderScheduledJobService] via `List<LeaderGuardedJob>`.
 */
@Component
class LockAssertJob : LeaderGuardedJob {

    override val lockName = "leader:lock-assert-demo"

    init {
        lockName.requireNotBlank("lockName")
    }

    override fun execute() {
        log.info { "[LockAssertJob] Starting — verifying lock ownership via LockAssert" }

        // Verify this instance holds the distributed lock before doing real work.
        // assertLocked() throws IllegalStateException if called outside runIfLeader context.
        LockAssert.assertLocked()
        log.info { "[LockAssertJob] assertLocked() passed — this instance is the elected leader" }

        // isLocked() is a non-throwing alternative for conditional guards.
        if (LockAssert.isLocked()) {
            log.info { "[LockAssertJob] isLocked() == true — safe to proceed with leader-only work" }
        }

        // Named variant: assertLocked(lockName) verifies ownership of a specific lock.
        LockAssert.assertLocked(lockName)
        log.info { "[LockAssertJob] assertLocked(\"$lockName\") passed" }

        simulateBlockingWork(ASSERTION_DEMO_DURATION)
        log.info { "[LockAssertJob] Job complete" }
    }

    companion object : KLogging() {
        private val ASSERTION_DEMO_DURATION: Duration = Duration.ofMillis(20)
    }
}
