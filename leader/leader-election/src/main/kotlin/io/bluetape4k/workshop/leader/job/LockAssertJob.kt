package io.bluetape4k.workshop.leader.job

import io.bluetape4k.leader.LockAssert
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * [LockAssert]를 사용한 lock ownership 검증을 보여주는 leader-guarded job입니다.
 *
 * [LockAssert.assertLocked]와 [LockAssert.isLocked]를 사용하면 job code가 runtime에
 * distributed lock을 이 instance가 아직 보유하는지 검증할 수 있습니다. 다음에 유용합니다.
 * - 비용이 큰 작업 전 early-exit guard.
 * - leader ownership을 가정하는 helper method의 방어적 검사.
 * - AOP wiring이 lock handle을 올바르게 전파하는지 검증하는 integration test.
 *
 * ## 동작 / 계약
 * - [LockAssert.assertLocked]는 `runIfLeader` context 밖에서 호출되면
 *   [IllegalStateException]을 던집니다. 즉 현재 thread에 lock handle이 없습니다.
 * - [LockAssert.isLocked]는 예외 대신 `false`를 반환하므로 guard condition으로 어디서나 안전하게 호출할 수 있습니다.
 * - 이 bean은 `@Component`로 등록되어 `List<LeaderGuardedJob>`를 통해
 *   [LeaderScheduledJobService]에 자동 주입됩니다.
 */
@Component
class LockAssertJob : LeaderGuardedJob {

    override val lockName = "leader:lock-assert-demo"

    init {
        lockName.requireNotBlank("lockName")
    }

    override fun execute() {
        log.info { "[LockAssertJob] Starting — verifying lock ownership via LockAssert" }

        // 실제 작업 전에 이 instance가 distributed lock을 보유하는지 검증합니다.
        // assertLocked()는 runIfLeader context 밖에서 호출하면 IllegalStateException을 던집니다.
        LockAssert.assertLocked()
        log.info { "[LockAssertJob] assertLocked() passed — this instance is the elected leader" }

        // isLocked()는 조건부 guard에 사용할 수 있는 non-throwing 대안입니다.
        if (LockAssert.isLocked()) {
            log.info { "[LockAssertJob] isLocked() == true — safe to proceed with leader-only work" }
        }

        // named variant인 assertLocked(lockName)는 특정 lock의 ownership을 검증합니다.
        LockAssert.assertLocked(lockName)
        log.info { "[LockAssertJob] assertLocked(\"$lockName\") passed" }

        simulateBlockingWork(ASSERTION_DEMO_DURATION)
        log.info { "[LockAssertJob] Job complete" }
    }

    companion object : KLogging() {
        private val ASSERTION_DEMO_DURATION: Duration = Duration.ofMillis(20)
    }
}
