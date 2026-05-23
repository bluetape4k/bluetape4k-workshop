package io.bluetape4k.workshop.leader.job

/**
 * Marker interface for a job that should execute only on the elected leader instance.
 *
 * ## Behavior / Contract
 * - [lockName] must be unique across all registered [LeaderGuardedJob] beans. Duplicate names
 *   are detected in [io.bluetape4k.workshop.leader.job.LeaderScheduledJobService]`s `init{}`.
 * - Implementations MUST validate [lockName] in their own `init {}` block:
 *   `lockName.requireNotBlank("lockName")`.
 * - [execute] is called by [io.bluetape4k.workshop.leader.job.LeaderScheduledJobService]
 *   only when this instance wins the distributed lock. Return type is always `Unit`; the
 *   "skipped" signal is expressed as a `null` result from `LeaderElector.runIfLeader`, not
 *   from [execute] itself.
 *
 * ## Usage example
 * ```kotlin
 * @Component
 * class MyCacheWarmupJob : LeaderGuardedJob {
 *     override val lockName = "leader:my-cache-warmup"
 *     init { lockName.requireNotBlank("lockName") }
 *
 *     override fun execute() {
 *         // warm up cache here
 *     }
 *
 *     companion object : KLogging()
 * }
 * ```
 */
interface LeaderGuardedJob {

    /**
     * Distributed lock key used to elect exactly one instance.
     * Must be non-blank and unique across all registered [LeaderGuardedJob] beans.
     */
    val lockName: String

    /**
     * Performs the job logic. Called only when this instance is the elected leader.
     * Exceptions thrown from [execute] propagate to the caller and release the lock immediately.
     */
    fun execute()
}
