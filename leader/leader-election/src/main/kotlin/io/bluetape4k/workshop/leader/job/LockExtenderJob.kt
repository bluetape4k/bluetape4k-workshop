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
 * [LockExtender]를 사용한 runtime lease extension을 보여주는 leader-guarded job입니다.
 *
 * job body가 configured lease TTL보다 오래 실행되면 job이 끝나기 전에 leader lock이 만료될 수 있고,
 * 다른 instance가 lock을 획득해 split-brain 실행을 일으킬 수 있습니다.
 * [LockExtender.extendActiveLock]은 job body 안에서 현재 lease를 연장해 이를 막습니다.
 *
 * ## 동작 / 계약
 * - 첫 번째 work phase 뒤에 [LockExtender.extendActiveLock]을 호출해 lease를 갱신합니다.
 * - lock을 더 이상 이 instance가 보유하지 않을 때만 `false`(extension failed)를 반환합니다.
 *   예를 들어 다른 node가 이미 takeover한 경우입니다. job은 warning을 남기고 계속 진행해 check pattern을 보여줍니다.
 * - 이 bean은 `@Component`로 등록되어 `List<LeaderGuardedJob>`를 통해
 *   [LeaderScheduledJobService]에 자동 주입됩니다.
 *
 * ## 사용
 * production에서는 오래 실행되는 job의 각 major phase 뒤에 `LockExtender.extendActiveLock()`을 호출하고,
 * 남은 예상 실행 시간을 extension delta로 사용합니다.
 */
@Component
class LockExtenderJob : LeaderGuardedJob {

    override val lockName = "leader:lock-extender-demo"

    init {
        lockName.requireNotBlank("lockName")
    }

    override fun execute() {
        log.info { "[LockExtenderJob] Starting — simulating a long-running leader job" }

        // Phase 1: 첫 번째 작업 단위
        simulateBlockingWork(PHASE_DURATION)
        log.debug { "[LockExtenderJob] Phase 1 complete" }

        // 실행 중 만료를 피하려고 Phase 2 전에 lease를 연장합니다.
        // extendActiveLock은 aspect가 capture한 현재 thread의 LeaderLockHandle을 사용합니다.
        val extended = LockExtender.extendActiveLock(EXTENSION_DURATION)
        if (extended) {
            log.info { "[LockExtenderJob] Lease extended by $EXTENSION_DURATION — continuing Phase 2" }
        } else {
            log.warn { "[LockExtenderJob] Lease extension failed (lock not held or lost) — proceeding anyway for demo" }
        }

        // Phase 2: 두 번째 작업 단위
        simulateBlockingWork(PHASE_DURATION)
        log.info { "[LockExtenderJob] Phase 2 complete — job finished" }
    }

    companion object : KLogging() {
        /** work phase 사이에 active lease에 추가하는 extension duration입니다. */
        val EXTENSION_DURATION: Duration = Duration.ofSeconds(30)
        private val PHASE_DURATION: Duration = Duration.ofMillis(50)
    }
}
