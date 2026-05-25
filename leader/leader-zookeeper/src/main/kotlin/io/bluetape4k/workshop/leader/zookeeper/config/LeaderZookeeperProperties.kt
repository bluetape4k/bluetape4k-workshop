package io.bluetape4k.workshop.leader.zookeeper.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

/**
 * Configuration properties for the ZooKeeper-based leader election workshop module.
 *
 * ## Behavior / Contract (R16 — ZooKeeper has no TTL)
 * ZooKeeper uses session-bound ephemeral znodes for leader election.
 * Unlike Redis-based election:
 * - There is no lease TTL; leadership is held until the ZooKeeper session expires or is explicitly closed.
 * - `leaseTime` and `autoExtend` are intentionally absent from this class.
 *   Setting `LeaderElectionOptions.autoExtend = true` emits a WARN log and is silently ignored.
 * - Session expiry (e.g., due to network partition or process crash) automatically removes
 *   the ephemeral election node, triggering re-election among competing candidates.
 * - Tune [ZooKeeperConfig.sessionTimeoutMs] relative to your job interval for acceptable failover latency.
 *
 * ## Production Considerations
 * - Default [ZooKeeperConfig.connectString] is `localhost:2181` (development only).
 * - Default [ZooKeeperConfig.sessionTimeoutMs] is 60 000 ms — the failover window on hard crash.
 * - ACL and TLS are omitted in this workshop; production deployments should configure an ACLProvider
 *   and SASL/TLS via `CuratorFrameworkFactory.builder()`.
 */
@ConfigurationProperties(prefix = "leader.zookeeper")
data class LeaderZookeeperProperties(
    val zookeeper: ZooKeeperConfig = ZooKeeperConfig(),
    val basePath: String = "/workshop/leader-zookeeper",
    val waitTime: Duration = Duration.ofSeconds(2),
    val groupMaxLeaders: Int = 2,
    val jobFixedDelay: String = "PT10S",
    val suspendJobFixedDelay: String = "PT12S",
    val groupJobFixedDelay: String = "PT15S",
    val suspendGroupJobFixedDelay: String = "PT18S",
    // NOTE: leaseTime / autoExtend intentionally absent — R16: ZooKeeper has no TTL
) : Serializable {
    companion object : KLogging() {
        private const val serialVersionUID = 1L
    }

    init {
        basePath.requireNotBlank("basePath")
        groupMaxLeaders.requirePositiveNumber("groupMaxLeaders")
        // connectString validated inside ZooKeeperConfig.init
    }

    /**
     * ZooKeeper connection configuration.
     *
     * @property connectString ZooKeeper connection string (host:port or host:port,host:port,...).
     * @property sessionTimeoutMs ZooKeeper session timeout in milliseconds. Controls failover latency on crash.
     * @property connectionTimeoutMs ZooKeeper connection establishment timeout in milliseconds.
     * @property blockUntilConnectedSeconds Maximum seconds to wait for initial ZooKeeper connection.
     */
    data class ZooKeeperConfig(
        val connectString: String = "localhost:2181",
        val sessionTimeoutMs: Int = 60_000,
        val connectionTimeoutMs: Int = 15_000,
        val blockUntilConnectedSeconds: Long = 10,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }

        init {
            connectString.requireNotBlank("connectString")
            sessionTimeoutMs.requirePositiveNumber("sessionTimeoutMs")
            connectionTimeoutMs.requirePositiveNumber("connectionTimeoutMs")
            blockUntilConnectedSeconds.requirePositiveNumber("blockUntilConnectedSeconds")
        }
    }
}
