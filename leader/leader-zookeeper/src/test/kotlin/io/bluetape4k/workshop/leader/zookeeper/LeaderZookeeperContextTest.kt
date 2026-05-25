package io.bluetape4k.workshop.leader.zookeeper

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderElector
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderGroupElector
import io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderElector
import io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderGroupElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.infra.ZooKeeperServer
import io.bluetape4k.workshop.leader.zookeeper.service.BlockingLeaderService
import io.bluetape4k.workshop.leader.zookeeper.service.GroupLeaderService
import io.bluetape4k.workshop.leader.zookeeper.service.SuspendGroupLeaderService
import io.bluetape4k.workshop.leader.zookeeper.service.SuspendLeaderZkService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * T0: Spring Boot context loading test for the leader-zookeeper workshop module.
 *
 * Verifies that all 4 elector beans and 4 leader service beans are wired correctly.
 * Catches `@Component`/`@Bean` omissions, type mismatches, and configuration property
 * binding failures at runtime before they reach production.
 *
 * Uses `@DynamicPropertySource` to inject the Testcontainers ZooKeeper URL.
 *
 * NOTE: The Spring property key is `leader.zookeeper.zookeeper.connect-string`
 * (the outer `leader.zookeeper` prefix + the inner nested `zookeeper.connectString` field).
 */
@SpringBootTest
class LeaderZookeeperContextTest(
    @Autowired val leaderElector: ZooKeeperLeaderElector,
    @Autowired val suspendLeaderElector: ZooKeeperSuspendLeaderElector,
    @Autowired val leaderGroupElector: ZooKeeperLeaderGroupElector,
    @Autowired val suspendLeaderGroupElector: ZooKeeperSuspendLeaderGroupElector,
    @Autowired val blockingLeaderService: BlockingLeaderService,
    @Autowired val suspendLeaderService: SuspendLeaderZkService,
    @Autowired val groupLeaderService: GroupLeaderService,
    @Autowired val suspendGroupLeaderService: SuspendGroupLeaderService,
) {
    companion object : KLogging() {
        val zookeeper: ZooKeeperServer = ZooKeeperServer.Launcher.zookeeper

        @JvmStatic
        @DynamicPropertySource
        fun zkProperties(registry: DynamicPropertyRegistry) {
            registry.add("leader.zookeeper.zookeeper.connect-string") { zookeeper.url }
        }
    }

    @Test
    fun `Spring context loads all 4 electors and 4 services`() {
        leaderElector.shouldNotBeNull()
        suspendLeaderElector.shouldNotBeNull()
        leaderGroupElector.shouldNotBeNull()
        suspendLeaderGroupElector.shouldNotBeNull()

        blockingLeaderService.shouldNotBeNull()
        suspendLeaderService.shouldNotBeNull()
        groupLeaderService.shouldNotBeNull()
        suspendGroupLeaderService.shouldNotBeNull()
    }
}
