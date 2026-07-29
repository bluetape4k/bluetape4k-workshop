package io.bluetape4k.workshop.leader.service

import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import kotlinx.coroutines.delay
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

/**
 * [LettuceSuspendLeaderElector]를 사용해 coroutine-first leadership을 보여주는 leader election service입니다.
 *
 * multi-instance 배포에서 `suspend` leader job은 lock acquisition 대기 중 blocking을 제거합니다.
 * 선출된 leader instance만 work body를 실행하고, 나머지는 `runIfLeader`에서 `null`을 받아 조용히 skip합니다.
 *
 * ## 동작 / 계약
 * - 이 instance가 lock을 얻지 못하면 [runIfLeader]는 `null`을 반환합니다(skipped).
 * - [runIfLeader]는 lock-acquisition 대기 동안 thread를 blocking하지 않고 caller를 suspend합니다.
 * - `@Scheduled` 메서드는 Spring scheduler boundary에서 coroutine 호출을 `kotlinx.coroutines.runBlocking`으로 감쌉니다.
 *   scheduler thread가 이 호출에 전용되므로 여기서는 blocking이 허용됩니다.
 * - [executionCount]는 테스트용으로 노출하며 production 용도가 아닙니다.
 */
@Service
class SuspendLeaderService(
    private val suspendLeaderElector: LettuceSuspendLeaderElector,
) {
    /** 이 instance가 선출되어 leader action을 실행한 횟수입니다. */
    val executionCount = AtomicInteger(0)

    companion object : KLogging() {
        const val LOCK_NAME = "leader:suspend-demo"
    }

    /**
     * coroutine leader action을 실행합니다.
     *
     * 이 instance가 선출되면 action 결과를 반환하고, skip되면 `null`을 반환합니다.
     * `runTest {}`를 사용하는 테스트에서 직접 쓰기 적합합니다.
     */
    suspend fun runLeaderWork(): String? =
        suspendLeaderElector.runIfLeader(LOCK_NAME) {
            log.info { "[SuspendLeaderService] Coroutine leader work started" }
            delay(20) // simulate async I/O without blocking a thread
            executionCount.incrementAndGet()
            log.info { "[SuspendLeaderService] Coroutine leader work complete (total=${executionCount.get()})" }
            "done"
        }

    /**
     * scheduled 진입점입니다. blocking Spring scheduler thread를 coroutine으로 연결합니다.
     *
     * `kotlinx.coroutines.runBlocking`은 scheduler boundary에서만 사용합니다.
     * 실제 leader work는 모두 [runLeaderWork]를 통해 suspend function으로 실행됩니다.
     */
    @Scheduled(fixedDelayString = "\${leader.suspend-job-fixed-delay:PT15S}")
    fun runScheduled() {
        log.debug { "[SuspendLeaderService] Scheduled trigger — entering coroutine" }
        kotlinx.coroutines.runBlocking {
            val result = runLeaderWork()
            if (result != null) {
                log.info { "[SuspendLeaderService] [LEADER] Scheduled coroutine job executed: result=$result" }
            } else {
                log.debug { "[SuspendLeaderService] [SKIPPED] Not the elected leader this round" }
            }
        }
    }
}
