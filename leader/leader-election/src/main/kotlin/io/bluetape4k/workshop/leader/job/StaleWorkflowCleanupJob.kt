package io.bluetape4k.workshop.leader.job

import io.bluetape4k.logging.*
import io.bluetape4k.support.requireNotBlank
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * leader-guarded job 예제: stale workflow cleanup입니다.
 *
 * timeout되었거나 abandoned된 workflow의 cleanup을 simulation합니다. multi-instance 배포에서
 * double-deletion을 피하려면 이 job은 정확히 하나의 instance에서만 실행되어야 합니다.
 *
 * ## 동작 / 계약
 * - [lockName]은 `init {}`에서 non-blank로 검증합니다.
 * - [execute]는 cleanup message를 log로 남기고 짧은 delay를 simulation합니다.
 * - 이 bean은 Spring `@Component`로 등록되므로 auto-discovery되고
 *   `List<LeaderGuardedJob>`를 통해 [LeaderScheduledJobService]에 주입됩니다.
 */
@Component
class StaleWorkflowCleanupJob : LeaderGuardedJob {

    override val lockName = "leader:stale-workflow-cleanup"

    init {
        lockName.requireNotBlank("lockName")
    }

    override fun execute() {
        log.info { "[StaleWorkflowCleanupJob] Scanning for stale workflows on elected leader instance" }
        // cleanup을 simulation합니다(예: timed-out workflow record를 CANCELLED로 표시).
        simulateBlockingWork(CLEANUP_DURATION)
        log.info { "[StaleWorkflowCleanupJob] Stale workflow cleanup complete" }
    }

    companion object : KLogging() {
        private val CLEANUP_DURATION: Duration = Duration.ofMillis(50)
    }
}
