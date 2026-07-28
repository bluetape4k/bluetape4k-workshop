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
import io.bluetape4k.codec.Base58
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * ZooKeeper 리더 선출 테스트의 추상 기반 클래스이다.
 *
 * companion object에서 하나의 공유 [CuratorFramework]를 사용하며, JVM마다 한 번 lazy 초기화한다.
 * InterProcessMutex 소유권은 ZooKeeper 세션이 아니라 Thread를 기준으로 관리되므로,
 * 같은 [curator] 인스턴스로 경쟁하는 모든 worker가 lock을 두고 올바르게 경합한다.
 *
 * lazy 초기화는 첫 테스트가 접근하기 전에 ZooKeeper 컨테이너가 시작되도록 보장한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractLeaderZookeeperTest {

    companion object : KLogging() {
        val zookeeper: ZooKeeperServer = ZooKeeperServer.Launcher.zookeeper

        /** 공유 [CuratorFramework]이다. InterProcessMutex가 Thread 기준이므로 스레드/코루틴 사이에 공유해도 안전하다. */
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

    // waitTime = 500ms: CI 타이밍 흔들림을 흡수할 여유 시간을 둔다.
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

    fun randomLockName(prefix: String = "t") = "$prefix:${Base58.randomString(8)}"
}
