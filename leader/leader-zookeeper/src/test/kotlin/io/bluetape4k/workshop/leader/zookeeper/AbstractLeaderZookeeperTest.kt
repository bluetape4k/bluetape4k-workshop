package io.bluetape4k.workshop.leader.zookeeper

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderElector
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderGroupElector
import io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderElector
import io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderGroupElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.infra.ZooKeeperServer
import io.bluetape4k.utils.ShutdownQueue
import org.apache.curator.framework.CuratorFramework
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Abstract base for ZooKeeper leader election tests.
 *
 * Uses a single shared [CuratorFramework] in the companion object (initialized lazily once per JVM).
 * InterProcessMutex ownership is keyed on Thread, not ZooKeeper session, so all workers
 * competing via the same [curator] instance will correctly contend for the lock.
 *
 * Lazy initialization ensures the ZooKeeper container is started before the first test accesses it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractLeaderZookeeperTest {

    companion object : KLogging() {
        val zookeeper: ZooKeeperServer = ZooKeeperServer.Launcher.zookeeper

        /** Shared CuratorFramework — safe to share across threads/coroutines (InterProcessMutex is Thread-keyed). */
        val curator: CuratorFramework by lazy {
            ZooKeeperServer.Launcher.getCuratorFramework(zookeeper).also {
                it.start()
                check(it.blockUntilConnected(10, TimeUnit.SECONDS)) {
                    "Test curator could not connect to ZooKeeper within 10s (url=${zookeeper.url})"
                }
                ShutdownQueue.register { it.close() }
            }
        }
    }

    // waitTime = 500ms: provides margin for CI timing jitter
    fun newElector(basePath: String = "/test/single") =
        ZooKeeperLeaderElector(curator, basePath, LeaderElectionOptions(waitTime = 500.milliseconds))

    fun newSuspendElector(basePath: String = "/test/single-suspend") =
        ZooKeeperSuspendLeaderElector(curator, basePath, LeaderElectionOptions(waitTime = 500.milliseconds))

    fun newGroupElector(maxLeaders: Int = 2, basePath: String = "/test/group") =
        ZooKeeperLeaderGroupElector(
            client = curator,
            options = LeaderGroupElectionOptions(maxLeaders = maxLeaders, waitTime = 500.milliseconds),
            basePath = basePath
        )

    fun newSuspendGroupElector(maxLeaders: Int = 2, basePath: String = "/test/group-suspend") =
        ZooKeeperSuspendLeaderGroupElector(
            client = curator,
            options = LeaderGroupElectionOptions(maxLeaders = maxLeaders, waitTime = 500.milliseconds),
            basePath = basePath
        )

    fun randomLockName(prefix: String = "t") = "$prefix:${UUID.randomUUID()}"
}
