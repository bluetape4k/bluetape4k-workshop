package io.bluetape4k.workshop.leader.zookeeper

import io.bluetape4k.workshop.leader.zookeeper.config.LeaderZookeeperProperties
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * T9: [LeaderZookeeperProperties] `init {}` validation tests.
 *
 * Pure unit tests — no Spring context, no ZooKeeper container. Verifies that the
 * constructor rejects invalid configurations early so misconfiguration cannot reach
 * production.
 */
class LeaderZookeeperPropertiesValidationTest {

    @Test
    fun `blank basePath throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderZookeeperProperties(basePath = "")
        }
    }

    @Test
    fun `zero groupMaxLeaders throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderZookeeperProperties(groupMaxLeaders = 0)
        }
    }

    @Test
    fun `blank connectString throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderZookeeperProperties(
                zookeeper = LeaderZookeeperProperties.ZooKeeperConfig(connectString = "")
            )
        }
    }
}
