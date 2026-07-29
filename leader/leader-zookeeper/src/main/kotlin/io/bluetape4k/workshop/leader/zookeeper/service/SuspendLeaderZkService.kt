package io.bluetape4k.workshop.leader.zookeeper.service

import io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.leader.zookeeper.config.LeaderZookeeperProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ZooKeeperSuspendLeaderElector]를 사용하는 코루틴 네이티브 단일 리더 워크숍 서비스이다.
 *
 * ## 동작 / 계약
 * - [runLeaderWork]는 ZooKeeper lock을 기다리는 동안 suspend되며, 스레드를 블로킹하지 않는다.
 * - 리더로 선출되면 작업 결과를 반환하고, 건너뛰면 `null`을 반환한다.
 * - `@Scheduled` 진입점은 Spring의 블로킹 scheduler 스레드를 `runBlocking { ... }`으로
 *   코루틴에 연결하며, 협력적 취소가 scheduler로 전파되도록 [CancellationException]을 반드시 다시 던져야 한다.
 * - [executionCount]는 테스트 확인용으로만 노출하며 운영 코드에서 사용하지 않는다.
 */
@Service
class SuspendLeaderZkService(
    private val elector: ZooKeeperSuspendLeaderElector,
    @Suppress("unused") private val props: LeaderZookeeperProperties,
) {
    companion object : KLoggingChannel() {
        const val LOCK_NAME = "workshop:suspend-job"
    }

    /** 이 인스턴스가 리더로 선출되어 리더 작업을 실행한 횟수이다. */
    val executionCount = AtomicInteger(0)

    /**
     * suspend 가능한 리더 작업이다. 테스트의 `runTest { ... }` 안에서 호출해도 안전하다.
     *
     * @return 이 인스턴스가 lock을 획득하면 `"done"`, 건너뛰면 `null`이다.
     */
    suspend fun runLeaderWork(lockName: String = LOCK_NAME): String? {
        lockName.requireNotBlank("lockName")
        return elector.runIfLeader(lockName) {
            executionCount.incrementAndGet()
            log.info { "[LEADER] SuspendLeaderZkService coroutine work started" }
            delay(20) // 스레드를 블로킹하지 않는 비동기 I/O를 흉내 낸다.
            log.info { "[LEADER] SuspendLeaderZkService coroutine work complete (total=${executionCount.get()})" }
            "done"
        }.also {
            if (it == null) log.debug { "[SKIPPED] SuspendLeaderZkService not the elected leader" }
        }
    }

    /**
     * 스케줄러 진입점이다.
     *
     * [CancellationException]은 다시 던지고(CLAUDE.md: never swallow cancellation),
     * 다른 예외는 로그로 남겨 일시적 실패 뒤에도 scheduler 스레드가 살아 있도록 한다.
     */
    @Scheduled(fixedDelayString = "\${leader.zookeeper.suspend-job-fixed-delay:PT12S}")
    fun runScheduled() {
        try {
            runBlocking { runLeaderWork() }
        } catch (e: CancellationException) {
            throw e // 반드시 다시 던진다. CLAUDE.md: never swallow CancellationException
        } catch (e: Exception) {
            log.warn(e) { "Scheduled suspend leader work failed" }
        }
    }
}
