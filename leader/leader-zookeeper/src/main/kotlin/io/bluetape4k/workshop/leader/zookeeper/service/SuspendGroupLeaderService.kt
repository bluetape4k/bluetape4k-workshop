package io.bluetape4k.workshop.leader.zookeeper.service

import io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderGroupElector
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
 * [ZooKeeperSuspendLeaderGroupElector]를 사용하는 코루틴 네이티브 그룹 리더 워크숍 서비스이다.
 *
 * ## 동작 / 계약
 * - 최대 `maxLeaders`개 인스턴스가 동시에 작업 본문을 실행할 수 있다.
 * - [runLeaderWork]는 사용 가능한 slot을 기다리는 동안 suspend되며, 스레드를 블로킹하지 않는다.
 * - slot 진입이 허용되면 작업 결과를 반환하고, 대기 시간 안에 사용할 수 있는 slot이 없으면 `null`을 반환한다.
 * - `@Scheduled` 진입점은 Spring의 블로킹 scheduler 스레드를 `runBlocking { ... }`으로
 *   코루틴에 연결하며, 협력적 취소가 scheduler로 전파되도록 [CancellationException]을 반드시 다시 던져야 한다.
 * - [executionCount]는 테스트 확인용으로만 노출하며 운영 코드에서 사용하지 않는다.
 */
@Service
class SuspendGroupLeaderService(
    private val elector: ZooKeeperSuspendLeaderGroupElector,
    @Suppress("unused") private val props: LeaderZookeeperProperties,
) {
    companion object : KLoggingChannel() {
        const val LOCK_NAME = "workshop:suspend-group-job"
    }

    /** 이 인스턴스가 leader slot에 진입한 횟수이다. */
    val executionCount = AtomicInteger(0)

    /**
     * suspend 가능한 그룹 리더 작업이다. 테스트의 `runTest { ... }` 안에서 호출해도 안전하다.
     *
     * @return 이 인스턴스가 slot에 진입하면 `"done"`, 사용할 수 있는 slot이 없으면 `null`이다.
     */
    suspend fun runLeaderWork(lockName: String = LOCK_NAME): String? {
        lockName.requireNotBlank("lockName")
        return elector.runIfLeader(lockName) {
            executionCount.incrementAndGet()
            log.info { "[GROUP-LEADER] SuspendGroupLeaderService entered slot" }
            delay(20) // 스레드를 블로킹하지 않는 비동기 I/O를 흉내 낸다.
            log.info { "[GROUP-LEADER] SuspendGroupLeaderService slot work complete (total=${executionCount.get()})" }
            "done"
        }.also {
            if (it == null) log.debug { "[SKIPPED] SuspendGroupLeaderService — no slot available" }
        }
    }

    /**
     * 스케줄러 진입점이다.
     *
     * [CancellationException]은 다시 던지고(CLAUDE.md: never swallow cancellation),
     * 다른 예외는 로그로 남겨 일시적 실패 뒤에도 scheduler 스레드가 살아 있도록 한다.
     */
    @Scheduled(fixedDelayString = "\${leader.zookeeper.suspend-group-job-fixed-delay:PT18S}")
    fun runScheduled() {
        try {
            runBlocking { runLeaderWork() }
        } catch (e: CancellationException) {
            throw e // 반드시 다시 던진다. CLAUDE.md: never swallow CancellationException
        } catch (e: Exception) {
            log.warn(e) { "Scheduled suspend group leader work failed" }
        }
    }
}
