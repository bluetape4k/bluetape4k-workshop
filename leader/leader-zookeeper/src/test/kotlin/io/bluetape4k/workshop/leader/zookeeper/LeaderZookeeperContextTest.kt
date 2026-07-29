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
 * T0: leader-zookeeper 워크숍 모듈의 Spring Boot context 로딩 테스트이다.
 *
 * 네 개 elector 빈과 네 개 리더 서비스 빈이 모두 올바르게 연결되는지 검증한다.
 * `@Component`/`@Bean` 누락, 타입 불일치, 설정 프로퍼티 바인딩 실패가 운영에 도달하기 전에
 * 런타임에서 포착한다.
 *
 * `@DynamicPropertySource`로 Testcontainers ZooKeeper URL을 주입한다.
 *
 * 참고: Spring 프로퍼티 키는 `leader.zookeeper.zookeeper.connect-string`이다.
 * 이는 바깥 `leader.zookeeper` prefix와 안쪽 중첩 `zookeeper.connectString` 필드를 결합한 형태이다.
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
