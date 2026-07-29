package io.bluetape4k.workshop.leader.job

import io.bluetape4k.logging.*
import io.bluetape4k.support.requireNotBlank
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * leader-guarded job 예제: cache warmup입니다.
 *
 * multi-instance 배포에서 정확히 하나의 instance에서만 실행되어야 하는 cache warmup 작업을 simulation합니다.
 * `bluetape4k-leader` distributed lock을 사용해 단일 실행을 보장합니다.
 *
 * ## 동작 / 계약
 * - [lockName]은 `init {}`에서 non-blank로 검증합니다.
 * - [execute]는 warmup message를 log로 남기고 짧은 delay를 simulation합니다.
 * - 이 bean은 Spring `@Component`로 등록되므로 auto-discovery되고
 *   `List<LeaderGuardedJob>`를 통해 [LeaderScheduledJobService]에 주입됩니다.
 */
@Component
class CacheWarmupJob : LeaderGuardedJob {

    override val lockName = "leader:cache-warmup"

    init {
        lockName.requireNotBlank("lockName")
    }

    override fun execute() {
        log.info { "[CacheWarmupJob] Starting cache warmup on elected leader instance" }
        // entry warmup을 simulation합니다(예: product catalog, config data pre-loading).
        simulateBlockingWork(WARMUP_DURATION)
        log.info { "[CacheWarmupJob] Cache warmup complete" }
    }

    companion object : KLogging() {
        private val WARMUP_DURATION: Duration = Duration.ofMillis(50)
    }
}
