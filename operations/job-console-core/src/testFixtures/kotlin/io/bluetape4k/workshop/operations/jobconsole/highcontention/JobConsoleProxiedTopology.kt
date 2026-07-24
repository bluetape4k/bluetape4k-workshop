package io.bluetape4k.workshop.operations.jobconsole.highcontention

import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import eu.rekawek.toxiproxy.model.Toxic
import eu.rekawek.toxiproxy.model.ToxicDirection
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.testcontainers.infra.ToxiproxyServer
import io.bluetape4k.testcontainers.storage.RedisServer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.WaitStrategy
import org.testcontainers.containers.wait.strategy.WaitStrategyTarget
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.time.Duration

class HighContentionDockerResource(
    resourceKey: String,
    resourceType: String,
    labels: Map<String, String>,
) {
    val resourceKey: String =
        HighContentionArtifactPaths.requireIdentifier(resourceKey, "resourceKey")
    val resourceType: String =
        HighContentionArtifactPaths.requireIdentifier(resourceType, "resourceType")
    val labels: Map<String, String> = labels.toSortedMap()
    val journalFields: Map<String, String>

    init {
        this.labels.keys.requireEquals(REQUIRED_LABEL_KEYS, "labels.keys")
        this.labels.forEach { (key, value) ->
            key.requireNotBlank("label key")
            value.requireNotBlank("label value")
        }
        this.labels.getValue(RESOURCE_KEY_LABEL)
            .requireEquals(this.resourceKey, RESOURCE_KEY_LABEL)
        this.labels.getValue(RESOURCE_TYPE_LABEL)
            .requireEquals(this.resourceType, RESOURCE_TYPE_LABEL)
        journalFields = buildMap {
            put("resourceKey", this@HighContentionDockerResource.resourceKey)
            put("resourceType", this@HighContentionDockerResource.resourceType)
            this@HighContentionDockerResource.labels.forEach { (key, value) -> put("label.$key", value) }
        }
    }

    companion object {
        const val RUN_ID_LABEL = "io.bluetape4k.high-contention.run-id"
        const val PROFILE_ID_LABEL = "io.bluetape4k.high-contention.profile-id"
        const val RESOURCE_KEY_LABEL = "io.bluetape4k.high-contention.resource-key"
        const val RESOURCE_TYPE_LABEL = "io.bluetape4k.high-contention.resource-type"

        val REQUIRED_LABEL_KEYS: Set<String> = setOf(
            RUN_ID_LABEL,
            PROFILE_ID_LABEL,
            RESOURCE_KEY_LABEL,
            RESOURCE_TYPE_LABEL,
        )
    }
}

data class JobConsoleDockerResources(
    val network: HighContentionDockerResource,
    val redis: HighContentionDockerResource,
    val toxiproxy: HighContentionDockerResource,
) {
    val all: List<HighContentionDockerResource> = listOf(network, redis, toxiproxy)

    init {
        network.resourceType.requireEquals("network", "network.resourceType")
        redis.resourceType.requireEquals("container", "redis.resourceType")
        toxiproxy.resourceType.requireEquals("container", "toxiproxy.resourceType")
        all.map(HighContentionDockerResource::resourceKey)
            .toSet()
            .size
            .requireEquals(all.size, "resource keys")
        all.map { it.labels.valuesAt(RUN_PROFILE_LABEL_KEYS) }
            .distinct()
            .size
            .requireEquals(1, "run/profile labels")
    }

    private companion object {
        val RUN_PROFILE_LABEL_KEYS = listOf(
            HighContentionDockerResource.RUN_ID_LABEL,
            HighContentionDockerResource.PROFILE_ID_LABEL,
        )
    }
}

interface JobConsoleRedisConnection : AutoCloseable {
    fun ping(): String
}

class JobConsoleProxiedTopology private constructor(
    private val journal: HighContentionJournal,
    private val resources: JobConsoleDockerResources,
    private val cutpoint: JobConsoleTopologyCutpoint,
) : AutoCloseable {

    private var network: Network? = null
    private var redis: RedisServer? = null
    private var toxiproxy: ToxiproxyServer? = null
    private var proxy: Proxy? = null
    private var connectionReset: Toxic? = null
    private val connections = linkedSetOf<OwnedRedisConnection>()
    private var closed = false

    lateinit var redisUri: String
        private set

    @Synchronized
    fun openRedisConnection(): JobConsoleRedisConnection {
        check(!closed) { "proxied topology is closed" }
        check(::redisUri.isInitialized) { "proxied topology is not ready" }
        val endpoint = URI.create(redisUri)
        val socket = Socket().apply {
            connect(
                InetSocketAddress(endpoint.host, endpoint.port),
                CLIENT_TIMEOUT.toMillis().toInt(),
            )
            soTimeout = CLIENT_TIMEOUT.toMillis().toInt()
        }
        return try {
            OwnedRedisConnection(socket) { owned ->
                synchronized(this) {
                    connections.remove(owned)
                }
            }.also(connections::add)
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    @Synchronized
    fun cutExistingConnections() {
        check(!closed) { "proxied topology is closed" }
        check(connectionReset == null) { "existing connection cut is already active" }
        connectionReset = proxy().toxics().resetPeer(
            CONNECTION_RESET_TOXIC,
            ToxicDirection.DOWNSTREAM,
            0,
        )
    }

    @Synchronized
    fun restoreExistingConnections() {
        connectionReset?.remove()
        connectionReset = null
    }

    @Synchronized
    fun disableNewConnections() {
        check(!closed) { "proxied topology is closed" }
        proxy().disable()
    }

    @Synchronized
    fun enableNewConnections() {
        check(!closed) { "proxied topology is closed" }
        proxy().enable()
    }

    internal fun routesThroughProxy(): Boolean {
        val proxyServer = toxiproxy ?: return false
        val proxyUri = "redis://${proxyServer.host}:${proxyServer.getMappedPort(PROXY_PORT)}"
        val directRedis = redis ?: return false
        return redisUri == proxyUri &&
            redisUri != "redis://${directRedis.host}:${directRedis.port}"
    }

    override fun close() {
        val closeActions = synchronized(this) {
            if (closed) {
                return
            }
            closed = true
            listOf<() -> Unit>(
                { connections.toList().asReversed().forEach(OwnedRedisConnection::close) },
                { restoreExistingConnections() },
                { proxy?.enable() },
                { proxy?.delete() },
                { toxiproxy?.stop() },
                { redis?.stop() },
                { network?.close() },
            )
        }
        var failure: Throwable? = null
        closeActions.forEach { action ->
            try {
                action()
            } catch (error: Throwable) {
                if (failure == null) {
                    failure = error
                } else {
                    failure.addSuppressed(error)
                }
            }
        }
        failure?.let { throw it }
    }

    private fun start() {
        network = createNetwork(resources.network)
        redis = createRedis(resources.redis, network())
        toxiproxy = createToxiproxy(resources.toxiproxy, network())

        val proxyServer = toxiproxy()
        val client = awaitToxiproxyClient(proxyServer)
        proxy = client.createProxy(
            PROXY_NAME,
            "0.0.0.0:$PROXY_PORT",
            "$REDIS_NETWORK_ALIAS:${RedisServer.PORT}",
        )
        redisUri = "redis://${proxyServer.host}:${proxyServer.getMappedPort(PROXY_PORT)}"
        awaitProxiedRedis()
    }

    private fun awaitToxiproxyClient(server: ToxiproxyServer): ToxiproxyClient {
        val deadline = System.nanoTime() + READINESS_TIMEOUT.toNanos()
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            val client = ToxiproxyClient(server.host, server.controlPort)
            try {
                client.version()
                return client
            } catch (error: Exception) {
                lastFailure = error
                Thread.sleep(READINESS_POLL_INTERVAL.toMillis())
            }
        }
        throw IllegalStateException("Toxiproxy control API did not become ready", lastFailure)
    }

    private fun awaitProxiedRedis() {
        val deadline = System.nanoTime() + READINESS_TIMEOUT.toNanos()
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                openRedisConnection().use {
                    check(it.ping() == "PONG") { "proxied Redis path returned an invalid PING response" }
                }
                return
            } catch (error: Exception) {
                lastFailure = error
                Thread.sleep(READINESS_POLL_INTERVAL.toMillis())
            }
        }
        throw IllegalStateException("proxied Redis path did not become ready", lastFailure)
    }

    private fun createNetwork(resource: HighContentionDockerResource): Network {
        recordCreateIntent(resource)
        cutpoint.hit(JobConsoleTopologyCutpoint.Phase.BEFORE_CREATE, resource.resourceKey)
        val created = Network.builder()
            .createNetworkCmdModifier { command -> command.withLabels(resource.labels) }
            .build()
        return try {
            val id = created.id
            cutpoint.hit(JobConsoleTopologyCutpoint.Phase.AFTER_CREATE, resource.resourceKey)
            recordCreated(resource, id)
            created
        } catch (error: Throwable) {
            runCatching(created::close)
            throw error
        }
    }

    private fun createRedis(
        resource: HighContentionDockerResource,
        network: Network,
    ): RedisServer {
        recordCreateIntent(resource)
        cutpoint.hit(JobConsoleTopologyCutpoint.Phase.BEFORE_CREATE, resource.resourceKey)
        val created = RedisServer().apply {
            withNetwork(network)
            withNetworkAliases(REDIS_NETWORK_ALIAS)
            withLabels(resource.labels)
            waitingFor(NoReadinessWait)
        }
        return try {
            created.start()
            cutpoint.hit(JobConsoleTopologyCutpoint.Phase.AFTER_CREATE, resource.resourceKey)
            recordCreated(resource, created.containerId)
            created
        } catch (error: Throwable) {
            runCatching(created::stop)
            throw error
        }
    }

    private fun createToxiproxy(
        resource: HighContentionDockerResource,
        network: Network,
    ): ToxiproxyServer {
        recordCreateIntent(resource)
        cutpoint.hit(JobConsoleTopologyCutpoint.Phase.BEFORE_CREATE, resource.resourceKey)
        val created = ToxiproxyServer().apply {
            withNetwork(network)
            withLabels(resource.labels)
            waitingFor(NoReadinessWait)
        }
        return try {
            created.start()
            cutpoint.hit(JobConsoleTopologyCutpoint.Phase.AFTER_CREATE, resource.resourceKey)
            recordCreated(resource, created.containerId)
            created
        } catch (error: Throwable) {
            runCatching(created::stop)
            throw error
        }
    }

    private fun recordCreateIntent(resource: HighContentionDockerResource) {
        journal.append(
            HighContentionJournalEvent.DOCKER_CREATE_INTENT,
            resource.journalFields,
        )
    }

    private fun recordCreated(
        resource: HighContentionDockerResource,
        dockerObjectId: String,
    ) {
        journal.append(
            HighContentionJournalEvent.DOCKER_RESOURCE_CREATED,
            resource.journalFields + ("dockerObjectId" to dockerObjectId.requireNotBlank("dockerObjectId")),
        )
    }

    private fun network(): Network = checkNotNull(network) { "network is not allocated" }

    private fun toxiproxy(): ToxiproxyServer =
        checkNotNull(toxiproxy) { "Toxiproxy is not allocated" }

    private fun proxy(): Proxy = checkNotNull(proxy) { "Redis proxy is not allocated" }

    companion object {
        private const val REDIS_NETWORK_ALIAS = "redis-primary"
        private const val PROXY_NAME = "redis-primary-proxy"
        private const val PROXY_PORT = 8666
        private const val CONNECTION_RESET_TOXIC = "reset-existing-connections"
        private val CLIENT_TIMEOUT = Duration.ofSeconds(2)
        private val READINESS_TIMEOUT = Duration.ofSeconds(10)
        private val READINESS_POLL_INTERVAL = Duration.ofMillis(50)

        fun start(
            journal: HighContentionJournal,
            resources: JobConsoleDockerResources,
        ): JobConsoleProxiedTopology =
            start(journal, resources, JobConsoleTopologyCutpoint.NONE)

        internal fun start(
            journal: HighContentionJournal,
            resources: JobConsoleDockerResources,
            cutpoint: JobConsoleTopologyCutpoint,
        ): JobConsoleProxiedTopology {
            val topology = JobConsoleProxiedTopology(journal, resources, cutpoint)
            return try {
                topology.start()
                topology
            } catch (error: Throwable) {
                try {
                    topology.close()
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
                throw error
            }
        }
    }
}

internal fun interface JobConsoleTopologyCutpoint {
    fun hit(
        phase: Phase,
        resourceKey: String,
    )

    enum class Phase {
        BEFORE_CREATE,
        AFTER_CREATE,
    }

    companion object {
        val NONE = JobConsoleTopologyCutpoint { _, _ -> }

        fun beforeCreate(resourceKey: String): JobConsoleTopologyCutpoint =
            throwingAt(Phase.BEFORE_CREATE, resourceKey)

        fun afterCreate(resourceKey: String): JobConsoleTopologyCutpoint =
            throwingAt(Phase.AFTER_CREATE, resourceKey)

        private fun throwingAt(
            expectedPhase: Phase,
            expectedResourceKey: String,
        ): JobConsoleTopologyCutpoint =
            JobConsoleTopologyCutpoint { phase, resourceKey ->
                if (phase == expectedPhase && resourceKey == expectedResourceKey) {
                    throw ExpectedCutpointException()
                }
            }
    }
}

internal class ExpectedCutpointException : IllegalStateException("expected topology crash cutpoint")

private object NoReadinessWait : WaitStrategy {
    override fun waitUntilReady(waitStrategyTarget: WaitStrategyTarget) = Unit

    override fun withStartupTimeout(startupTimeout: Duration): WaitStrategy = this
}

private class OwnedRedisConnection(
    private val socket: Socket,
    private val onClosed: (OwnedRedisConnection) -> Unit,
) : JobConsoleRedisConnection {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
    private var closed = false

    @Synchronized
    override fun ping(): String {
        check(!closed) { "Redis connection is closed" }
        writer.write("*1\r\n${'$'}4\r\nPING\r\n")
        writer.flush()
        val response = reader.readLine() ?: error("Redis connection closed without a PING response")
        check(response.startsWith("+")) { "Redis PING failed" }
        return response.removePrefix("+")
    }

    @Synchronized
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        try {
            socket.close()
        } finally {
            onClosed(this)
        }
    }
}

private fun Map<String, String>.valuesAt(keys: List<String>): List<String> =
    keys.map(::getValue)
