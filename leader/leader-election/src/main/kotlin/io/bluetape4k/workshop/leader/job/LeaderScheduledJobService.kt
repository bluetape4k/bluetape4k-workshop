package io.bluetape4k.workshop.leader.job

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.logging.*
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Spring service that dispatches registered [LeaderGuardedJob] beans on a fixed schedule.
 *
 * Only the elected leader instance executes each job. Other instances skip silently.
 *
 * ## Behavior / Contract
 * - Duplicate [LeaderGuardedJob.lockName] values are detected in `init {}` and cause an
 *   [IllegalStateException] at startup — failing fast prevents silent split-brain execution.
 * - If no jobs are registered, a warning is logged (not an error).
 * - Each job is wrapped in an individual `try/catch`: one failing job does NOT prevent
 *   subsequent jobs from running.
 * - `runIfLeader` returns `null` when this instance did not win the lock (skipped);
 *   a non-null return indicates successful execution on the leader.
 *
 * ## Usage example
 * ```
 * # application.yml
 * leader:
 *   job-fixed-delay: PT10S
 * ```
 */
@Service
class LeaderScheduledJobService(
    private val leaderElector: LeaderElector,
    private val jobs: List<LeaderGuardedJob>,
) {
    init {
        val lockNames = jobs.map { it.lockName }
        val duplicates = lockNames.groupBy { it }.filter { it.value.size > 1 }.keys
        check(duplicates.isEmpty()) {
            "Duplicate lockNames detected in LeaderGuardedJob beans: $duplicates. " +
                "Each job must have a unique lockName."
        }
        if (jobs.isEmpty()) {
            log.warn { "No LeaderGuardedJob beans registered. The scheduler will run but skip all jobs." }
        } else {
            log.info { "LeaderScheduledJobService initialized with ${jobs.size} job(s): ${lockNames.joinToString()}" }
        }
    }

    /**
     * Runs all registered [LeaderGuardedJob] instances on a fixed delay.
     * Only the leader instance executes each job body; others skip.
     */
    @Scheduled(fixedDelayString = "\${leader.job-fixed-delay:PT10S}")
    fun runJobs() {
        jobs.forEach { job ->
            try {
                val result = leaderElector.runIfLeader(job.lockName) {
                    job.execute()
                }
                if (result != null) {
                    log.info { "[LEADER] Job '${job.lockName}' executed successfully on this instance" }
                } else {
                    log.debug { "[SKIPPED] Job '${job.lockName}' — this instance is not the elected leader" }
                }
            } catch (e: Exception) {
                log.error(e) { "[ERROR] Job '${job.lockName}' threw an exception: ${e.message}" }
            }
        }
    }

    companion object : KLogging()
}
