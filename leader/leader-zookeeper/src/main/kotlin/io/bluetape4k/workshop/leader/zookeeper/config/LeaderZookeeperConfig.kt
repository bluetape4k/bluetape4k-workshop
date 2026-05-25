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
 * Spring configuration for ZooKeeper-based leader election beans.
 *
 * ## Behavior / Contract
 * Creates a single shared [CuratorFramework] bean and four [io.bluetape4k.leader.LeaderElector] beans.
 * The [CuratorFramework] bean registers a [org.apache.curator.framework.state.ConnectionStateListener]
 * **before** `start()` to capture early connection transitions.
 *
 * ## Resource Lifecycle
 * If `blockUntilConnected` times out, `client.close()` is called explicitly to prevent
 * background thread leaks (Spring `destroyMethod` only fires on successfully registered beans).
 *
 * ## ACL Note
 * Default `OPEN_ACL_UNSAFE` — production deployments should configure
 * `CuratorFrameworkFactory.builder().aclProvider(DigestACLProvider(...))`.
 */
@Configuration
@EnableConfigurationProperties(LeaderZookeeperProperties::class)
class LeaderZookeeperConfig {

    companion object : KLogging()

    /**
     * Creates and starts a [CuratorFramework] connected to the configured ZooKeeper ensemble.
     *
     * Registers a [org.apache.curator.framework.state.ConnectionStateListener] before `start()`
     * so that SUSPENDED/LOST/RECONNECTED events are never missed.
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

        // Register BEFORE start() to avoid missing early transitions
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
            client.close() // explicit close to prevent background thread leak
            error(
                "ZooKeeper connection timeout after ${cfg.blockUntilConnectedSeconds}s " +
                    "(connectString=${cfg.connectString}). Check that ZooKeeper is running and accessible."
            )
        }

        // NOTE: OPEN_ACL_UNSAFE default — production: use CuratorFrameworkFactory.builder().aclProvider(...)
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
