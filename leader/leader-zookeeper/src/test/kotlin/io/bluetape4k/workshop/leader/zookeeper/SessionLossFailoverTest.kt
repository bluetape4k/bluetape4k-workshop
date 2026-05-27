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
 * T8 — Demonstrates R16: ZooKeeper session expiry triggers ephemeral node removal and re-election.
 *
 * ## Isolation Strategy
 *
 * This test does NOT extend [AbstractLeaderZookeeperTest] — inheriting it would expose the shared
 * Launcher singleton curator through companion-object methods and accidentally coupling other tests
 * to client-side session closure performed here.
 *
 * Uses client-side ZooKeeper session close (NOT container restart) to simulate session expiry:
 * `clientA.zookeeperClient.zooKeeper.close()` forces the ZK server to mark the session expired
 * and remove ephemeral nodes — equivalent to a network partition or process crash. Container
 * `stop()`/`start()` is avoided because `ZooKeeperServer(reuse=true)` remaps the host port,
 * leaving any existing Curator client in a stale state.
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
        // reuse=false so the container lifecycle is fully owned by this test class.
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

        // workerA acquires leadership in a background thread and holds it until interrupted.
        val workerAThread = Thread {
            val electorA = ZooKeeperLeaderElector(
                client = clientA,
                basePath = BASE_PATH,
                options = LeaderElectionOptions(waitTime = WAIT_TIME_SHORT)
            )
            try {
                electorA.runIfLeader(lockName) {
                    workerAHoldingLatch.countDown()  // signal: "I now hold the lock"
                    runCatching { Thread.sleep(60_000) }  // hold until session closed / interrupted
                    "A-done"
                }
            } finally {
                workerADoneLatch.countDown()
            }
        }
        workerAThread.start()

        // Wait until workerA holds the lock before triggering session loss.
        check(workerAHoldingLatch.await(10, TimeUnit.SECONDS)) {
            "workerA did not acquire leadership within 10s"
        }

        // Simulate session expiry via client-side ZooKeeper close — forces ZK server to expire
        // the session and remove ephemeral nodes; no container restart needed.
        clientA.zookeeperClient.zooKeeper.close()

        // workerB should be able to acquire leadership once workerA's session is expired
        // and the ephemeral node is removed.
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
