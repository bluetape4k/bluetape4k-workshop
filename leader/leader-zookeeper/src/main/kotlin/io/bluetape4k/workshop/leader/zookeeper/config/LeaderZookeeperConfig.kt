package io.bluetape4k.workshop.leader.zookeeper.config

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderElector
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderGroupElector
import io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderElector
import io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderGroupElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.framework.state.ConnectionState
import org.apache.curator.retry.ExponentialBackoffRetry
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit
import kotlin.time.toKotlinDuration

/**
 * ZooKeeper 기반 리더 선출 빈을 구성하는 Spring 설정이다.
 *
 * ## 동작 / 계약
 * 공유 [CuratorFramework] 빈 하나와 [io.bluetape4k.leader.LeaderElector] 빈 네 개를 만든다.
 * [CuratorFramework] 빈은 초기 연결 상태 전이를 놓치지 않도록 `start()` 호출 **전에**
 * [org.apache.curator.framework.state.ConnectionStateListener]를 등록한다.
 *
 * ## 리소스 생명주기
 * `blockUntilConnected`가 타임아웃되면 백그라운드 스레드 누수를 막기 위해
 * `client.close()`를 명시적으로 호출한다. Spring `destroyMethod`는 빈 등록이 성공한 경우에만 실행된다.
 *
 * ## ACL 참고
 * 기본값은 `OPEN_ACL_UNSAFE`이다. 운영 배포에서는
 * `CuratorFrameworkFactory.builder().aclProvider(DigestACLProvider(...))`를 구성해야 한다.
 */
@Configuration
@EnableConfigurationProperties(LeaderZookeeperProperties::class)
class LeaderZookeeperConfig {

    companion object : KLogging()

    /**
     * 설정된 ZooKeeper ensemble에 연결되는 [CuratorFramework]를 만들고 시작한다.
     *
     * SUSPENDED/LOST/RECONNECTED 이벤트를 놓치지 않도록 `start()` 전에
     * [org.apache.curator.framework.state.ConnectionStateListener]를 등록한다.
     */
    @Bean(destroyMethod = "close")
    fun curatorFramework(props: LeaderZookeeperProperties): CuratorFramework {
        val cfg = props.zookeeper
        val client = CuratorFrameworkFactory.newClient(
            cfg.connectString,
            cfg.sessionTimeoutMs,
            cfg.connectionTimeoutMs,
            ExponentialBackoffRetry(1000, 3)
        )

        // 초기 상태 전이를 놓치지 않도록 start() 전에 등록한다.
        client.connectionStateListenable.addListener { _, newState ->
            when (newState) {
                ConnectionState.SUSPENDED ->
                    log.warn { "ZooKeeper connection SUSPENDED — leadership uncertain" }
                ConnectionState.LOST ->
                    log.error { "ZooKeeper session LOST — ephemeral nodes removed; re-election will occur" }
                ConnectionState.RECONNECTED ->
                    log.info { "ZooKeeper session RECONNECTED" }
                else ->
                    log.debug { "ZooKeeper connection state: $newState" }
            }
        }

        client.start()

        if (!client.blockUntilConnected(cfg.blockUntilConnectedSeconds.toInt(), TimeUnit.SECONDS)) {
            client.close() // 백그라운드 스레드 누수를 막기 위해 명시적으로 닫는다.
            error(
                "ZooKeeper connection timeout after ${cfg.blockUntilConnectedSeconds}s " +
                    "(connectString=${cfg.connectString}). Check that ZooKeeper is running and accessible."
            )
        }

        // 참고: 기본값은 OPEN_ACL_UNSAFE이다. 운영에서는 CuratorFrameworkFactory.builder().aclProvider(...)를 사용한다.
        return client
    }

    @Bean
    fun leaderElector(
        curator: CuratorFramework,
        props: LeaderZookeeperProperties,
    ): ZooKeeperLeaderElector =
        ZooKeeperLeaderElector(
            client = curator,
            basePath = "${props.basePath}/single",
            options = LeaderElectionOptions(waitTime = props.waitTime.toKotlinDuration())
        )

    @Bean
    fun suspendLeaderElector(
        curator: CuratorFramework,
        props: LeaderZookeeperProperties,
    ): ZooKeeperSuspendLeaderElector =
        ZooKeeperSuspendLeaderElector(
            client = curator,
            basePath = "${props.basePath}/single-suspend",
            options = LeaderElectionOptions(waitTime = props.waitTime.toKotlinDuration())
        )

    @Bean
    fun leaderGroupElector(
        curator: CuratorFramework,
        props: LeaderZookeeperProperties,
    ): ZooKeeperLeaderGroupElector =
        ZooKeeperLeaderGroupElector(
            client = curator,
            options = LeaderGroupElectionOptions(
                maxLeaders = props.groupMaxLeaders,
                waitTime = props.waitTime.toKotlinDuration()
            ),
            basePath = "${props.basePath}/group"
        )

    @Bean
    fun suspendLeaderGroupElector(
        curator: CuratorFramework,
        props: LeaderZookeeperProperties,
    ): ZooKeeperSuspendLeaderGroupElector =
        ZooKeeperSuspendLeaderGroupElector(
            client = curator,
            options = LeaderGroupElectionOptions(
                maxLeaders = props.groupMaxLeaders,
                waitTime = props.waitTime.toKotlinDuration()
            ),
            basePath = "${props.basePath}/group-suspend"
        )
}
