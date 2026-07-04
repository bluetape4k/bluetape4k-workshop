package io.bluetape4k.workshop.leader.job

import io.bluetape4k.logging.*
import io.bluetape4k.support.requireNotBlank
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Example leader-guarded job: cache warmup.
 *
 * Simulates a cache warmup operation that should run on exactly one instance
 * in a multi-instance deployment. Uses `bluetape4k-leader` distributed lock
 * to guarantee single execution.
 *
 * ## Behavior / Contract
 * - [lockName] is validated non-blank in `init {}`.
 * - [execute] logs a warmup message and simulates a short delay.
 * - This bean is registered as a Spring `@Component` so it is auto-discovered
 *   and injected into [LeaderScheduledJobService] via `List<LeaderGuardedJob>`.
 */
@Component
class CacheWarmupJob : LeaderGuardedJob {

    override val lockName = "leader:cache-warmup"

    init {
        lockName.requireNotBlank("lockName")
    }

    override fun execute() {
        log.info { "[CacheWarmupJob] Starting cache warmup on elected leader instance" }
        // Simulate warming up entries (e.g., pre-loading product catalog, config data)
        simulateBlockingWork(WARMUP_DURATION)
        log.info { "[CacheWarmupJob] Cache warmup complete" }
    }

    companion object : KLogging() {
        private val WARMUP_DURATION: Duration = Duration.ofMillis(50)
    }
}
