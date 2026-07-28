package io.bluetape4k.workshop.leader.zookeeper.service

import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderGroupElector
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
 * [ZooKeeperLeaderGroupElector]를 사용하는 블로킹 그룹 리더 워크숍 서비스이다.
 *
 * ## 동작 / 계약
 * - 최대 `maxLeaders`개 인스턴스가 동시에 작업 본문을 실행할 수 있다.
 * - 이 인스턴스가 slot에 진입하면 [runLeaderWork]는 작업 결과를 반환하고,
 *   대기 시간 안에 모든 slot이 사용 중이면 `null`을 반환한다.
 * - [inspectState]는 lock의 현재 [LeaderGroupState]를 반환한다. 테스트에서
 *   `activeCount`와 `availableSlots`를 검증할 때 유용하다.
 * - [executionCount]는 테스트 확인용으로만 노출하며 운영 코드에서 사용하지 않는다.
 */
@Service
class GroupLeaderService(
    private val elector: ZooKeeperLeaderGroupElector,
    @Suppress("unused") private val props: LeaderZookeeperProperties,
) {
    companion object : KLogging() {
        const val LOCK_NAME = "workshop:group-job"
    }

    /** 이 인스턴스가 leader slot에 진입한 횟수이다. */
    val executionCount = AtomicInteger(0)

    /**
     * [lockName]에 대한 leader slot 진입을 시도하고, 허용된 경우 작업 본문을 실행한다.
     *
     * @return 이 인스턴스가 slot에 진입하면 `"done"`, 사용할 수 있는 slot이 없으면 `null`이다.
     */
    fun runLeaderWork(lockName: String = LOCK_NAME): String? {
        lockName.requireNotBlank("lockName")
        return elector.runIfLeader(lockName) {
            executionCount.incrementAndGet()
            log.info { "[GROUP-LEADER] GroupLeaderService entered slot (total=${executionCount.get()})" }
            "done"
        }.also {
            if (it == null) log.debug { "[SKIPPED] GroupLeaderService — no slot available" }
        }
    }

    /**
     * [lockName]의 현재 그룹 선출 상태를 조회한다.
     *
     * 테스트와 운영 대시보드가 사용할 수 있도록 `activeCount`와 `availableSlots`를 드러낸다.
     */
    fun inspectState(lockName: String = LOCK_NAME): LeaderGroupState {
        lockName.requireNotBlank("lockName")
        return elector.state(lockName)
    }

    /**
     * 스케줄러 진입점이다.
     *
     * Spring scheduler 스레드가 반복 실행 사이에도 정상 상태를 유지하도록 취소가 아닌 예외를 잡는다.
     */
    @Scheduled(fixedDelayString = "\${leader.zookeeper.group-job-fixed-delay:PT15S}")
    fun runScheduled() {
        try {
            runLeaderWork()
        } catch (e: Exception) {
            log.warn(e) { "Scheduled group leader work failed" }
        }
    }
}
