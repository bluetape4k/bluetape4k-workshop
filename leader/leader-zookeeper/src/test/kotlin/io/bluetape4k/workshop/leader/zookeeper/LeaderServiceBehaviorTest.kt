package io.bluetape4k.workshop.leader.zookeeper

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.leader.zookeeper.config.LeaderZookeeperProperties
import io.bluetape4k.workshop.leader.zookeeper.service.BlockingLeaderService
import io.bluetape4k.workshop.leader.zookeeper.service.GroupLeaderService
import org.junit.jupiter.api.Test

/**
 * Behavioral tests for [BlockingLeaderService] and [GroupLeaderService].
 *
 * Constructs service instances directly (no Spring context) using the shared [curator]
 * from [AbstractLeaderZookeeperTest]. Verifies that each service correctly delegates
 * to the underlying elector and updates its [BlockingLeaderService.executionCount].
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

        // Acquire a slot so state is non-trivially populated
        service.runLeaderWork(lockName)

        val state = service.inspectState(lockName)
        state.shouldNotBeNull()
    }
}
