package io.bluetape4k.workshop.leader.zookeeper

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.leader.zookeeper.config.LeaderZookeeperProperties
import io.bluetape4k.workshop.leader.zookeeper.service.BlockingLeaderService
import io.bluetape4k.workshop.leader.zookeeper.service.GroupLeaderService
import org.junit.jupiter.api.Test

/**
 * [BlockingLeaderService]와 [GroupLeaderService]의 동작 테스트이다.
 *
 * Spring context 없이 [AbstractLeaderZookeeperTest]의 공유 [curator]로 서비스 인스턴스를 직접 구성한다.
 * 각 서비스가 하위 elector에 올바르게 위임하고 [BlockingLeaderService.executionCount]를 갱신하는지 검증한다.
 */
class LeaderServiceBehaviorTest : AbstractLeaderZookeeperTest() {

    companion object : KLogging()

    private val defaultProps = LeaderZookeeperProperties()

    @Test
    fun `BlockingLeaderService runLeaderWork returns done and increments executionCount`() {
        val elector = newElector(basePath = "/test/svc/blocking")
        val service = BlockingLeaderService(elector, defaultProps)

        val result = service.runLeaderWork(randomLockName("svc-blocking"))

        result shouldBeEqualTo "done"
        service.executionCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `BlockingLeaderService runLeaderWork increments executionCount on repeated calls`() {
        val elector = newElector(basePath = "/test/svc/blocking-repeat")
        val service = BlockingLeaderService(elector, defaultProps)
        val lockName = randomLockName("svc-blocking-repeat")

        repeat(3) { service.runLeaderWork(lockName) }

        service.executionCount.get() shouldBeEqualTo 3
    }

    @Test
    fun `GroupLeaderService runLeaderWork returns done and increments executionCount`() {
        val elector = newGroupElector(maxLeaders = 1, basePath = "/test/svc/group")
        val service = GroupLeaderService(elector, defaultProps)

        val result = service.runLeaderWork(randomLockName("svc-group"))

        result shouldBeEqualTo "done"
        service.executionCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `GroupLeaderService inspectState returns non-null LeaderGroupState`() {
        val elector = newGroupElector(maxLeaders = 2, basePath = "/test/svc/group-state")
        val service = GroupLeaderService(elector, defaultProps)
        val lockName = randomLockName("svc-state")

        // 상태가 비어 있지 않도록 slot을 하나 획득한다.
        service.runLeaderWork(lockName)

        val state = service.inspectState(lockName)
        state.shouldNotBeNull()
    }
}
