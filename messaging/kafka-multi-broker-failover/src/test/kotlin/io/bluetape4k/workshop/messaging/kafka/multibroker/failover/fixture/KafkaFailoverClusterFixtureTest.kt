package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class KafkaFailoverClusterFixtureTest {

    @Test
    fun `fixture owns a single lifecycle and closes idempotently`() {
        val network = FakeNetwork()
        val brokers = FakeBrokerFactory()
        val fixture = KafkaFailoverClusterFixture(
            runId = "unit-lifecycle",
            networkFactory = { network },
            brokerFactory = brokers,
            startCoordinator = FakeStartCoordinator(),
        )

        fixture.state shouldBeEqualTo KafkaFailoverLifecycleState.NEW
        fixture.start() shouldBeEqualTo fixture
        fixture.state shouldBeEqualTo KafkaFailoverLifecycleState.RUNNING
        fixture.brokers.keys.toList() shouldBeEqualTo listOf(1, 2, 3)

        fixture.close()
        fixture.state shouldBeEqualTo KafkaFailoverLifecycleState.CLOSED
        fixture.close()

        network.closeCount shouldBeEqualTo 1
        brokers.values.values.forEach { it.stopCount shouldBeEqualTo 1 }
    }

    @Test
    fun `second owner cannot start while first owner is starting`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = BlockingStartCoordinator(entered, release)
        val fixture = KafkaFailoverClusterFixture(
            runId = "unit-owner",
            networkFactory = { FakeNetwork() },
            brokerFactory = FakeBrokerFactory(),
            startCoordinator = coordinator,
        )

        val owner = thread(start = true) {
            fixture.start()
        }
        entered.await(1, TimeUnit.SECONDS).shouldBeTrue()

        assertFailsWith<IllegalStateException> {
            fixture.start()
        }

        release.countDown()
        owner.join(1_000)
        fixture.state shouldBeEqualTo KafkaFailoverLifecycleState.RUNNING
        fixture.close()
    }

    @Test
    fun `partial start and post validation failure roll back all resources`() {
        val brokers = FakeBrokerFactory(failNode = 2)
        val fixture = KafkaFailoverClusterFixture(
            runId = "unit-partial",
            networkFactory = { FakeNetwork() },
            brokerFactory = brokers,
            startCoordinator = FakeStartCoordinator(),
        )

        assertFailsWith<IllegalStateException> { fixture.start() }
        fixture.state shouldBeEqualTo KafkaFailoverLifecycleState.CLOSED
        brokers.values.values.forEach { it.stopCount shouldBeEqualTo 1 }

        val validationBrokers = FakeBrokerFactory()
        val validationFixture = KafkaFailoverClusterFixture(
            runId = "unit-validation",
            networkFactory = { FakeNetwork() },
            brokerFactory = validationBrokers,
            startCoordinator = FakeStartCoordinator(),
            postStartValidator = { error("post-validation failed") },
        )

        assertFailsWith<IllegalStateException> { validationFixture.start() }
        validationFixture.state shouldBeEqualTo KafkaFailoverLifecycleState.CLOSED
        validationBrokers.values.values.forEach { it.stopCount shouldBeEqualTo 1 }
    }

    @Test
    fun `stop and restart replace only the requested broker`() {
        val brokers = FakeBrokerFactory()
        val fixture = KafkaFailoverClusterFixture(
            runId = "unit-replacement",
            networkFactory = { FakeNetwork() },
            brokerFactory = brokers,
            startCoordinator = FakeStartCoordinator(),
        ).start()
        val original = brokers.values.getValue(2)

        fixture.stopBroker(2).isRunning.shouldBeFalse()
        fixture.brokers.containsKey(2).shouldBeFalse()

        fixture.restartBroker(2).isRunning.shouldBeTrue()
        fixture.brokers.getValue(2) shouldBeEqualTo brokers.lastCreated
        original.stopCount shouldBeEqualTo 1
        brokers.values.getValue(2).startCount shouldBeEqualTo 1

        fixture.close()
    }

    @Test
    fun `cancellation stops start handle and waits for quiescence`() {
        val coordinator = CancellableStartCoordinator()
        val brokers = FakeBrokerFactory()
        val fixture = KafkaFailoverClusterFixture(
            runId = "unit-cancel",
            networkFactory = { FakeNetwork() },
            brokerFactory = brokers,
            startCoordinator = coordinator,
        )

        coordinator.failure = java.util.concurrent.CancellationException("cancelled")
        assertFailsWith<java.util.concurrent.CancellationException> { fixture.start() }
        coordinator.cancelCount shouldBeEqualTo 1
        coordinator.quiesced.shouldBeTrue()
        fixture.state shouldBeEqualTo KafkaFailoverLifecycleState.CLOSED
    }

    private class FakeNetwork : KafkaFailoverNetwork {
        var closeCount: Int = 0
            private set

        override val containerNetwork: org.testcontainers.containers.Network? = null

        override fun close() {
            closeCount += 1
        }
    }

    private class FakeBroker(
        override val nodeId: Int,
        override val alias: String,
        private val failOnStart: Boolean,
    ) : KafkaFailoverBroker {
        var startCount: Int = 0
        var stopCount: Int = 0
        override var isRunning: Boolean = false
        override val bootstrapServers: String = "127.0.0.1:${19000 + nodeId}"
        override val imageReference: String = KafkaFailoverClusterFixture.IMAGE_REFERENCE
        override val repoDigests: List<String> = listOf(imageReference)

        override fun start() {
            startCount += 1
            if (failOnStart) error("broker-$nodeId start failed")
            isRunning = true
        }

        override fun stop() {
            stopCount += 1
            isRunning = false
        }
    }

    private class FakeBrokerFactory(
        private val failNode: Int? = null,
    ) : (Int, String, KafkaFailoverNetwork, String) -> KafkaFailoverBroker {
        val values = linkedMapOf<Int, FakeBroker>()
        var lastCreated: KafkaFailoverBroker? = null

        override fun invoke(
            nodeId: Int,
            alias: String,
            network: KafkaFailoverNetwork,
            runId: String,
        ): KafkaFailoverBroker = FakeBroker(nodeId, alias, nodeId == failNode).also {
            values[nodeId] = it
            lastCreated = it
        }
    }

    private class FakeStartCoordinator : KafkaFailoverStartCoordinator {
        override fun start(
            brokers: Collection<KafkaFailoverBroker>,
            deadline: KafkaFailoverDeadline,
        ): KafkaFailoverStartHandle = object : KafkaFailoverStartHandle {
            override fun await() {
                brokers.forEach { it.start() }
            }

            override fun cancel() = Unit

            override fun awaitQuiescence(deadline: KafkaFailoverDeadline): Boolean = true
        }
    }

    private class BlockingStartCoordinator(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : KafkaFailoverStartCoordinator {
        override fun start(
            brokers: Collection<KafkaFailoverBroker>,
            deadline: KafkaFailoverDeadline,
        ): KafkaFailoverStartHandle = object : KafkaFailoverStartHandle {
            override fun await() {
                entered.countDown()
                release.await(1, TimeUnit.SECONDS)
                brokers.forEach { it.start() }
            }

            override fun cancel() = Unit

            override fun awaitQuiescence(deadline: KafkaFailoverDeadline): Boolean = true
        }
    }

    private class CancellableStartCoordinator : KafkaFailoverStartCoordinator {
        var failure: Throwable? = null
        var cancelCount: Int = 0
        var quiesced: Boolean = false

        override fun start(
            brokers: Collection<KafkaFailoverBroker>,
            deadline: KafkaFailoverDeadline,
        ): KafkaFailoverStartHandle = object : KafkaFailoverStartHandle {
            override fun await() {
                failure?.let { throw it }
                brokers.forEach { it.start() }
            }

            override fun cancel() {
                cancelCount += 1
            }

            override fun awaitQuiescence(deadline: KafkaFailoverDeadline): Boolean {
                quiesced = true
                return true
            }
        }
    }
}
