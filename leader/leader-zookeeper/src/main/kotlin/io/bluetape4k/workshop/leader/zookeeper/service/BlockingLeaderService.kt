package io.bluetape4k.workshop.leader.zookeeper.service

import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.leader.zookeeper.config.LeaderZookeeperProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ZooKeeperLeaderElector]를 사용하는 블로킹 단일 리더 워크숍 서비스이다.
 *
 * ## 동작 / 계약
 * - 이 인스턴스가 리더로 선출되면 [runLeaderWork]는 작업 결과를 반환하고, 건너뛰면 `null`을 반환한다.
 * - `@Scheduled` 진입점은 Spring scheduler 스레드가 중단되지 않도록 취소가 아닌 예외를 로그로 남기고 삼킨다.
 * - [executionCount]는 테스트 확인용으로만 노출하며 운영 코드에서 사용하지 않는다.
 */
@Service
class BlockingLeaderService(
    private val elector: ZooKeeperLeaderElector,
    @Suppress("unused") private val props: LeaderZookeeperProperties,
) {
    companion object : KLogging() {
        const val LOCK_NAME = "workshop:blocking-job"
    }

    /** 이 인스턴스가 리더로 선출되어 리더 작업을 실행한 횟수이다. */
    val executionCount = AtomicInteger(0)

    /**
     * [lockName]에 대한 리더십 획득을 시도하고, 선출된 경우 작업 본문을 실행한다.
     *
     * @return 이 인스턴스가 lock을 획득하면 `"done"`, 건너뛰면 `null`이다.
     */
    fun runLeaderWork(lockName: String = LOCK_NAME): String? {
        lockName.requireNotBlank("lockName")
        return elector.runIfLeader(lockName) {
            executionCount.incrementAndGet()
            log.info { "[LEADER] BlockingLeaderService running work (total=${executionCount.get()})" }
            "done"
        }.also {
            if (it == null) log.debug { "[SKIPPED] BlockingLeaderService not the elected leader" }
        }
    }

    /**
     * 스케줄러 진입점이다.
     *
     * Spring scheduler 스레드가 반복 실행 사이에도 정상 상태를 유지하도록 취소가 아닌 예외를 잡는다.
     */
    @Scheduled(fixedDelayString = "\${leader.zookeeper.job-fixed-delay:PT10S}")
    fun runScheduled() {
        try {
            runLeaderWork()
        } catch (e: Exception) {
            log.warn(e) { "Scheduled leader work failed" }
        }
    }
}
