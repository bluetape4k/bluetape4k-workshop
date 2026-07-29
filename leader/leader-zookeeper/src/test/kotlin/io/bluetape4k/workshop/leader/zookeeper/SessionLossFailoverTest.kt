package io.bluetape4k.workshop.leader.zookeeper

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.infra.ZooKeeperServer
import org.apache.curator.framework.CuratorFramework
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import io.bluetape4k.codec.Base58
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * T8 - ZooKeeper 세션 만료가 ephemeral node 제거와 재선출을 유발한다는 R16을 보여준다.
 *
 * ## 격리 전략
 *
 * 이 테스트는 [AbstractLeaderZookeeperTest]를 상속하지 않는다. 상속하면 companion-object 메서드를 통해
 * 공유 Launcher singleton curator가 노출되어, 여기서 수행하는 client-side 세션 종료가 다른 테스트와
 * 의도치 않게 결합될 수 있다.
 *
 * 세션 만료는 컨테이너 재시작이 아니라 client-side ZooKeeper 세션 종료로 시뮬레이션한다.
 * `clientA.zookeeperClient.zooKeeper.close()`는 ZK 서버가 해당 세션을 만료 처리하고
 * ephemeral node를 제거하게 만든다. 이는 네트워크 분리나 프로세스 크래시와 동등한 상황이다.
 * `ZooKeeperServer(reuse=true)`에서는 `stop()`/`start()`가 호스트 port를 다시 매핑해
 * 기존 Curator client를 stale 상태로 남길 수 있으므로 컨테이너 재시작을 피한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionLossFailoverTest {

    companion object: KLogging() {
        private const val BASE_PATH = "/test/t8"
        private val WAIT_TIME_SHORT = 500.milliseconds
        private val WAIT_TIME_LONG = 10_000.milliseconds
    }

    private lateinit var isolatedZk: ZooKeeperServer
    private lateinit var clientA: CuratorFramework
    private lateinit var clientB: CuratorFramework

    fun randomLockName(prefix: String = "t8") = "$prefix:${Base58.randomString(8)}"

    @BeforeAll
    fun startIsolatedZk() {
        // 이 테스트 클래스가 컨테이너 생명주기를 완전히 소유하도록 reuse=false를 사용한다.
        isolatedZk = ZooKeeperServer(reuse = false).also { it.start() }
        clientA = ZooKeeperServer.Launcher.getCuratorFramework(isolatedZk).also {
            it.start()
            check(it.blockUntilConnected(10, TimeUnit.SECONDS)) { "clientA connection timeout" }
        }
        clientB = ZooKeeperServer.Launcher.getCuratorFramework(isolatedZk).also {
            it.start()
            check(it.blockUntilConnected(10, TimeUnit.SECONDS)) { "clientB connection timeout" }
        }
    }

    @AfterAll
    fun stopIsolatedZk() {
        runCatching { clientA.close() }
        runCatching { clientB.close() }
        runCatching { isolatedZk.stop() }
    }

    @Test
    fun `session loss causes leadership failover`() {
        val lockName = randomLockName()
        val workerAHoldingLatch = CountDownLatch(1)
        val workerADoneLatch = CountDownLatch(1)

        // workerA는 백그라운드 스레드에서 리더십을 획득하고 interrupt될 때까지 유지한다.
        val workerAThread = Thread {
            val electorA = ZooKeeperLeaderElector(
                client = clientA,
                basePath = BASE_PATH,
                options = LeaderElectionOptions(waitTime = WAIT_TIME_SHORT)
            )
            try {
                electorA.runIfLeader(lockName) {
                    workerAHoldingLatch.countDown()  // 신호: "지금 lock을 보유 중이다"
                    runCatching { Thread.sleep(60_000) }  // 세션 종료 또는 interrupt까지 유지한다.
                    "A-done"
                }
            } finally {
                workerADoneLatch.countDown()
            }
        }
        workerAThread.start()

        // 세션 손실을 유발하기 전에 workerA가 lock을 보유할 때까지 기다린다.
        check(workerAHoldingLatch.await(10, TimeUnit.SECONDS)) {
            "workerA did not acquire leadership within 10s"
        }

        // client-side ZooKeeper close로 세션 만료를 시뮬레이션한다.
        // ZK 서버가 세션을 만료시키고 ephemeral node를 제거하므로 컨테이너 재시작은 필요 없다.
        clientA.zookeeperClient.zooKeeper.close()

        // workerA의 세션이 만료되고 ephemeral node가 제거되면 workerB가 리더십을 획득할 수 있어야 한다.
        val electorB = ZooKeeperLeaderElector(
            client = clientB,
            basePath = BASE_PATH,
            options = LeaderElectionOptions(waitTime = WAIT_TIME_LONG)
        )
        await atMost Duration.ofSeconds(30) untilAsserted {
            val workerBResult = electorB.runIfLeader(lockName) { "B-acquired" }
            workerBResult shouldBeEqualTo "B-acquired"
        }

        workerAThread.interrupt()
        workerADoneLatch.await(5, TimeUnit.SECONDS)
    }
}
