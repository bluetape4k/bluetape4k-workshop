package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.lifecycle.Startable
import org.testcontainers.lifecycle.Startables
import org.testcontainers.utility.DockerImageName
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.nanoseconds

/** Testcontainers cluster lifecycle 상태입니다. */
enum class KafkaFailoverLifecycleState {
    NEW,
    STARTING,
    RUNNING,
    STOPPING,
    CLOSED,
}

/** unit test가 Docker에 의존하지 않도록 만든 작은 network abstraction입니다. */
interface KafkaFailoverNetwork : AutoCloseable {
    val containerNetwork: Network?
}

/** fixture lifecycle에 필요한 broker capability입니다. */
interface KafkaFailoverBroker {
    val nodeId: Int
    val alias: String
    val bootstrapServers: String
    val imageReference: String
    val repoDigests: List<String>
    var isRunning: Boolean

    fun start()

    fun stop()
}

/** cancellation과 quiescence 증거를 보존하는 aggregate start handle입니다. */
interface KafkaFailoverStartHandle {
    fun await()

    fun cancel()

    fun awaitQuiescence(deadline: KafkaFailoverDeadline): Boolean
}

fun interface KafkaFailoverStartCoordinator {
    fun start(
        brokers: Collection<KafkaFailoverBroker>,
        deadline: KafkaFailoverDeadline,
    ): KafkaFailoverStartHandle
}

data class KafkaFailoverBrokerSnapshot(
    val nodeId: Int,
    val alias: String,
    val bootstrapServers: String,
    val imageReference: String,
    val repoDigests: List<String>,
    val isRunning: Boolean,
)

/**
 * 격리된 3-broker KRaft cluster와 replacement lifecycle을 소유합니다.
 *
 * 기본 collaborator는 Testcontainers를 사용하지만 constructor collaborator는
 * 의도적으로 주입할 수 있어 lifecycle과 rollback test가 Docker를 요구하지 않습니다.
 */
class KafkaFailoverClusterFixture(
    val runId: String = defaultRunId(),
    private val networkFactory: () -> KafkaFailoverNetwork = ::createNetwork,
    private val brokerFactory: (Int, String, KafkaFailoverNetwork, String) -> KafkaFailoverBroker =
        ::createBroker,
    private val startCoordinator: KafkaFailoverStartCoordinator = DefaultStartCoordinator,
    private val postStartValidator: (KafkaFailoverClusterFixture) -> Unit = { it.validateStartedCluster() },
) : AutoCloseable {

    private val ownerLock = ReentrantLock()
    private val brokerRegistry = linkedMapOf<Int, KafkaFailoverBroker>()
    private var network: KafkaFailoverNetwork? = null
    private var startHandle: KafkaFailoverStartHandle? = null

    @Volatile
    var state: KafkaFailoverLifecycleState = KafkaFailoverLifecycleState.NEW
        private set

    /** 현재 등록된 broker의 안정적인 snapshot입니다. */
    val brokers: Map<Int, KafkaFailoverBroker>
        get() = ownerLock.withLock { brokerRegistry.toMap() }

    /** redacted CI summary writer가 사용하는 허용 목록 broker snapshot입니다. */
    fun brokerSnapshots(): List<KafkaFailoverBrokerSnapshot> = ownerLock.withLock {
        brokerRegistry.values.map { it.snapshot() }
    }

    /** [start] 이후에만 사용할 수 있는 Testcontainers network입니다. */
    val containerNetwork: Network?
        get() = network?.containerNetwork

    /** mapped loopback PLAINTEXT로 제한된 host-client bootstrap endpoint입니다. */
    val bootstrapServers: String
        get() = brokers.values
            .filter { it.isRunning }
            .sortedBy { it.nodeId }
            .joinToString(",") { it.bootstrapServers }
            .also { endpoints ->
                require(endpoints.isNotBlank()) { "no running Kafka broker bootstrap endpoint" }
                endpoints.split(',').forEach { endpoint ->
                    require(KafkaFailoverTopology.isHostAdvertisedListener("PLAINTEXT://$endpoint")) {
                        "non-loopback Kafka bootstrap endpoint is not allowed"
                    }
                }
            }

    fun start(): KafkaFailoverClusterFixture {
        if (!ownerLock.tryLock()) {
            throw IllegalStateException("Kafka failover fixture owner lock is held")
        }
        try {
            check(state == KafkaFailoverLifecycleState.NEW) {
                "fixture cannot start from state=$state"
            }
            state = KafkaFailoverLifecycleState.STARTING
            val createdNetwork = networkFactory().also { network = it }
            KafkaFailoverTopology.NODE_IDS.forEach { nodeId ->
                val alias = KafkaFailoverTopology.brokerAlias(nodeId)
                brokerRegistry[nodeId] = brokerFactory(nodeId, alias, createdNetwork, runId)
            }

            val moduleDeadline = KafkaFailoverDeadline.fromNow(KafkaFailoverDeadline.MODULE_TIMEOUT)
            val handle = startCoordinator.start(brokerRegistry.values, moduleDeadline)
            startHandle = handle
            handle.await()
            postStartValidator(this)
            state = KafkaFailoverLifecycleState.RUNNING
            return this
        } catch (error: Throwable) {
            rollbackAfterStartFailure(error)
            throw error
        } finally {
            startHandle = null
            ownerLock.unlock()
        }
    }

    fun stopBroker(nodeId: Int): KafkaFailoverBrokerSnapshot {
        ownerLock.withLock {
            check(state == KafkaFailoverLifecycleState.RUNNING) { "fixture is not running: $state" }
            val broker = brokerRegistry.remove(nodeId)
                ?: throw IllegalArgumentException("broker node is not registered: $nodeId")
            val snapshot = broker.snapshot()
            try {
                broker.stop()
                check(!broker.isRunning) { "broker-$nodeId remained running after stop" }
                return snapshot.copy(isRunning = false)
            } catch (error: Throwable) {
                brokerRegistry[nodeId] = broker
                throw error
            }
        }
    }

    fun restartBroker(nodeId: Int): KafkaFailoverBrokerSnapshot {
        ownerLock.withLock {
            check(state == KafkaFailoverLifecycleState.RUNNING) { "fixture is not running: $state" }
            check(nodeId in KafkaFailoverTopology.NODE_IDS) { "unsupported Kafka broker node id: $nodeId" }
            check(nodeId !in brokerRegistry) { "broker-$nodeId must be stopped before replacement" }
            val currentNetwork = network ?: error("fixture network is not initialized")
            val replacement = brokerFactory(nodeId, KafkaFailoverTopology.brokerAlias(nodeId), currentNetwork, runId)
            try {
                replacement.start()
                validateBroker(replacement)
                brokerRegistry[nodeId] = replacement
                return replacement.snapshot()
            } catch (error: Throwable) {
                runCatching { replacement.stop() }.onFailure { error.addSuppressed(it) }
                throw error
            }
        }
    }

    override fun close() {
        if (!ownerLock.tryLock()) {
            throw IllegalStateException("Kafka failover fixture owner lock is held")
        }
        try {
            if (state == KafkaFailoverLifecycleState.CLOSED) return
            state = KafkaFailoverLifecycleState.STOPPING
            var firstFailure: Throwable? = null
            brokerRegistry.values.toList().asReversed().forEach { broker ->
                runCatching { broker.stop() }
                    .onFailure { failure ->
                        if (firstFailure == null) firstFailure = failure else firstFailure!!.addSuppressed(failure)
                    }
            }
            brokerRegistry.clear()
            runCatching { network?.close() }
                .onFailure { failure ->
                    if (firstFailure == null) firstFailure = failure else firstFailure!!.addSuppressed(failure)
                }
            network = null
            state = KafkaFailoverLifecycleState.CLOSED
            firstFailure?.let { throw it }
        } finally {
            ownerLock.unlock()
        }
    }

    private fun rollbackAfterStartFailure(original: Throwable) {
        startHandle?.let { handle ->
            runCatching { handle.cancel() }.onFailure { original.addSuppressed(it) }
            val cleanupDeadline = KafkaFailoverDeadline.fromNow(KafkaFailoverDeadline.CLEANUP_TIMEOUT)
            if (!runCatching { handle.awaitQuiescence(cleanupDeadline) }.getOrDefault(false)) {
                original.addSuppressed(TimeoutException("Kafka failover start did not reach quiescence"))
            }
        }
        brokerRegistry.values.toList().asReversed().forEach { broker ->
            runCatching { broker.stop() }.onFailure { original.addSuppressed(it) }
        }
        brokerRegistry.clear()
        runCatching { network?.close() }.onFailure { original.addSuppressed(it) }
        network = null
        state = KafkaFailoverLifecycleState.CLOSED
    }

    private fun validateStartedCluster() {
        check(brokerRegistry.keys == KafkaFailoverTopology.NODE_IDS.toSet()) {
            "Kafka failover cluster must expose exactly three broker nodes"
        }
        brokerRegistry.values.forEach(::validateBroker)
    }

    private fun validateBroker(broker: KafkaFailoverBroker) {
        check(broker.isRunning) { "broker-${broker.nodeId} is not running" }
        require(broker.alias == KafkaFailoverTopology.brokerAlias(broker.nodeId)) {
            "broker alias drift for node ${broker.nodeId}"
        }
        validateRepoDigests(broker.repoDigests)
        require(KafkaFailoverTopology.isHostAdvertisedListener("PLAINTEXT://${broker.bootstrapServers}")) {
            "broker ${broker.nodeId} exposes a non-loopback host endpoint"
        }
    }

    private fun KafkaFailoverBroker.snapshot() = KafkaFailoverBrokerSnapshot(
        nodeId = nodeId,
        alias = alias,
        bootstrapServers = bootstrapServers,
        imageReference = imageReference,
        repoDigests = repoDigests.toList(),
        isRunning = isRunning,
    )

    companion object {
        const val APPROVED_IMAGE_DIGEST: String =
            "9516fb7634bad307d17c33b589fde9023003b0cb761374f500002b980a3149b9"
        const val IMAGE_REFERENCE: String = "apache/kafka@sha256:$APPROVED_IMAGE_DIGEST"
        const val RUN_ID_LABEL: String = "bluetape4k.kafka-failover.run-id"
        const val NODE_ID_LABEL: String = "bluetape4k.kafka-failover.node-id"

        fun validateRepoDigests(repoDigests: List<String>) {
            require(repoDigests.size == 1) { "Kafka image must expose exactly one RepoDigest" }
            require(repoDigests.single() == IMAGE_REFERENCE) {
                "Kafka image RepoDigest does not match the approved immutable reference"
            }
        }

        private fun defaultRunId(): String =
            (System.getProperty("KAFKA_FAILOVER_RUN_ID") ?: System.getenv("KAFKA_FAILOVER_RUN_ID"))
                ?.takeIf(String::isNotBlank)
                ?: "local-${System.currentTimeMillis()}-${Thread.currentThread().id}"

        private fun createNetwork(): KafkaFailoverNetwork = TestcontainersNetwork(Network.newNetwork())

        private fun createBroker(
            nodeId: Int,
            alias: String,
            network: KafkaFailoverNetwork,
            runId: String,
        ): KafkaFailoverBroker {
            val delegate = network.containerNetwork ?: error("Testcontainers network is required")
            return TestcontainersKafkaBroker(nodeId, alias, runId, delegate)
        }
    }

    private object DefaultStartCoordinator : KafkaFailoverStartCoordinator {
        override fun start(
            brokers: Collection<KafkaFailoverBroker>,
            deadline: KafkaFailoverDeadline,
        ): KafkaFailoverStartHandle {
            val settled = brokers.associateWith { AtomicBoolean(false) }
            val startables = brokers.map { broker ->
                object : Startable {
                    override fun start() {
                        try {
                            broker.start()
                        } finally {
                            settled.getValue(broker).set(true)
                        }
                    }

                    override fun stop() = broker.stop()
                }
            }
            val future = Startables.deepStart(startables)
            return object : KafkaFailoverStartHandle {
                override fun await() {
                    val remaining = deadline.remainingNanos()
                    if (remaining <= 0L) throw TimeoutException("Kafka startup deadline exhausted")
                    try {
                        future.get(remaining, TimeUnit.NANOSECONDS)
                    } catch (error: java.util.concurrent.ExecutionException) {
                        throw (error.cause ?: error)
                    }
                }

                override fun cancel() {
                    future.cancel(true)
                }

                override fun awaitQuiescence(deadline: KafkaFailoverDeadline): Boolean {
                    while (settled.values.any { !it.get() }) {
                        if (deadline.remainingNanos() <= 0L) return false
                        Thread.sleep(minOf(10L, deadline.remainingNanos().nanoseconds.inWholeMilliseconds.coerceAtLeast(1L)))
                    }
                    return true
                }
            }
        }
    }
}

private class TestcontainersNetwork(
    override val containerNetwork: Network,
) : KafkaFailoverNetwork {
    override fun close() = containerNetwork.close()
}

private class TestcontainersKafkaBroker(
    override val nodeId: Int,
    override val alias: String,
    runId: String,
    network: Network,
) : KafkaFailoverBroker {
    private val container = KafkaContainer(DockerImageName.parse(KafkaFailoverClusterFixture.IMAGE_REFERENCE)).apply {
        withNetwork(network)
        withNetworkAliases(alias)
        withEnv(KafkaFailoverTopology.brokerEnvironment(nodeId))
        withLabels(
            mapOf(
                KafkaFailoverClusterFixture.RUN_ID_LABEL to runId,
                KafkaFailoverClusterFixture.NODE_ID_LABEL to nodeId.toString(),
            ),
        )
        waitingFor(
            Wait.forLogMessage(".*Transitioning from RECOVERY to RUNNING.*", 1)
                .withStartupTimeout(Duration.ofSeconds(45)),
        )
        withCreateContainerCmdModifier { command ->
            val hostConfig = command.hostConfig ?: com.github.dockerjava.api.model.HostConfig()
            command.withHostConfig(
                hostConfig.withPortBindings(
                    com.github.dockerjava.api.model.PortBinding(
                        com.github.dockerjava.api.model.Ports.Binding.bindIp("127.0.0.1"),
                        com.github.dockerjava.api.model.ExposedPort.tcp(9092),
                    ),
                ),
            )
        }
    }

    override val bootstrapServers: String
        get() = container.bootstrapServers

    override val imageReference: String = KafkaFailoverClusterFixture.IMAGE_REFERENCE

    override val repoDigests: List<String>
        get() = container.dockerClient.inspectImageCmd(imageReference).exec().repoDigests ?: emptyList()

    override var isRunning: Boolean
        get() = container.isRunning
        set(value) = Unit

    override fun start() {
        container.start()
        validateLoopbackBinding(container)
    }

    override fun stop() {
        if (container.isRunning) container.stop()
    }

    private fun validateLoopbackBinding(container: KafkaContainer) {
        val info = container.dockerClient.inspectContainerCmd(container.containerId).exec()
        val bindings = info.hostConfig?.portBindings?.bindings?.values
            ?.flatMap { it.toList() }
            .orEmpty()
        bindings.forEach { binding ->
            require(binding.hostIp in setOf("127.0.0.1", "::1")) {
                "Kafka host binding is not loopback-only"
            }
        }
        val host = container.host
        require(InetAddress.getAllByName(host).all(InetAddress::isLoopbackAddress)) {
            "Kafka container host is not loopback-resolvable"
        }
    }
}
