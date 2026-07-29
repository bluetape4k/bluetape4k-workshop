package io.bluetape4k.workshop.leader.job

/**
 * 선출된 leader instance에서만 실행되어야 하는 job의 marker interface입니다.
 *
 * ## 동작 / 계약
 * - [lockName]은 등록된 모든 [LeaderGuardedJob] bean 사이에서 고유해야 합니다. 중복 이름은
 *   [io.bluetape4k.workshop.leader.job.LeaderScheduledJobService]의 `init{}`에서 감지합니다.
 * - 구현체는 자체 `init {}` block에서 반드시 [lockName]을 검증해야 합니다.
 *   `lockName.requireNotBlank("lockName")`.
 * - [execute]는 이 instance가 distributed lock을 획득한 경우에만
 *   [io.bluetape4k.workshop.leader.job.LeaderScheduledJobService]가 호출합니다. 반환 타입은 항상
 *   `Unit`입니다. "skipped" signal은 [execute] 자체가 아니라 `LeaderElector.runIfLeader`의
 *   `null` 결과로 표현합니다.
 *
 * ## 사용 예
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
     * 정확히 하나의 instance를 선출하는 데 사용하는 distributed lock key입니다.
     * non-blank여야 하며 등록된 모든 [LeaderGuardedJob] bean 사이에서 고유해야 합니다.
     */
    val lockName: String

    /**
     * job logic을 수행합니다. 이 instance가 선출된 leader일 때만 호출됩니다.
     * [execute]에서 던진 예외는 caller로 전파되고 lock을 즉시 해제합니다.
     */
    fun execute()
}
