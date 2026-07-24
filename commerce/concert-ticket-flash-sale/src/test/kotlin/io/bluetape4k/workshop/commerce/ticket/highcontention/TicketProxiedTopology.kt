package io.bluetape4k.workshop.commerce.ticket.highcontention

import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import eu.rekawek.toxiproxy.model.Toxic
import eu.rekawek.toxiproxy.model.ToxicDirection
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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal interface TicketRedisProbeConnection : AutoCloseable {
    fun ping(): String
}

internal fun interface TicketTopologyCutpoint {
    fun hit(phase: Phase, resourceKey: String)

    enum class Phase {
        BEFORE_CREATE,
        AFTER_CREATE,
    }

    companion object {
        val NONE = TicketTopologyCutpoint { _, _ -> }
    }
}

internal class TicketProxiedTopology private constructor(
    private val runId: String,
    private val profileId: String,
    private val journal: TicketHighContentionJournal,
    private val cutpoint: TicketTopologyCutpoint,
) : AutoCloseable {
    private var network: Network? = null
    private var redis: RedisServer? = null
    private var toxiproxy: ToxiproxyServer? = null
    private var proxy: Proxy? = null
    private var reset: Toxic? = null
    private val connections = linkedSetOf<OwnedConnection>()
    private val stateLock = ReentrantLock()
    private var closed = false

    lateinit var redisUri: String
        private set

    fun routesOnlyThroughProxy(): Boolean {
        val redisServer = redis ?: return false
        val proxyServer = toxiproxy ?: return false
        return redisUri == "redis://${proxyServer.host}:${proxyServer.getMappedPort(PROXY_PORT)}" &&
            redisUri != "redis://${redisServer.host}:${redisServer.port}"
    }

    fun openConnection(): TicketRedisProbeConnection = stateLock.withLock {
        check(!closed) { "Ticket topology is closed" }
        check(::redisUri.isInitialized) { "Ticket topology is not ready" }
        val endpoint = URI.create(redisUri)
        val socket = Socket().apply {
            connect(InetSocketAddress(endpoint.host, endpoint.port), CLIENT_TIMEOUT.toMillis().toInt())
            soTimeout = CLIENT_TIMEOUT.toMillis().toInt()
        }
        return try {
            OwnedConnection(socket) { owned ->
                stateLock.withLock {
                    connections.remove(owned)
                }
            }.also(connections::add)
        } catch (error: Throwable) {
            socket.close()
            throw error
        }
    }

    fun cutExistingConnections() = stateLock.withLock {
        check(reset == null) { "existing connection cut is already active" }
        reset = requireNotNull(proxy).toxics().resetPeer(
            RESET_TOXIC,
            ToxicDirection.DOWNSTREAM,
            0,
        )
    }

    fun disableNewConnections() = stateLock.withLock {
        requireNotNull(proxy).disable()
    }

    fun recover() = stateLock.withLock {
        reset?.remove()
        reset = null
        requireNotNull(proxy).enable()
    }

    override fun close() {
        val actions = stateLock.withLock {
            if (closed) {
                return
            }
            closed = true
            listOf<() -> Unit>(
                { connections.toList().asReversed().forEach(OwnedConnection::close) },
                { reset?.remove(); reset = null },
                { proxy?.enable() },
                { proxy?.delete() },
                { toxiproxy?.stop() },
                { redis?.stop() },
                { network?.close() },
            )
        }
        var failure: Throwable? = null
        actions.forEach { action ->
            try {
                action()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
    }

    private fun start() {
        network = createNetwork(resource("network", "network"))
        redis = createRedis(resource("redis", "container"), requireNotNull(network))
        toxiproxy = createToxiproxy(resource("toxiproxy", "container"), requireNotNull(network))
        val proxyServer = requireNotNull(toxiproxy)
        val client = awaitClient(proxyServer)
        proxy = client.createProxy(
            PROXY_NAME,
            "0.0.0.0:$PROXY_PORT",
            "$REDIS_ALIAS:${RedisServer.PORT}",
        )
        redisUri = "redis://${proxyServer.host}:${proxyServer.getMappedPort(PROXY_PORT)}"
        awaitRedis()
    }

    private fun resource(key: String, type: String): TicketDockerResource =
        TicketDockerResource(
            resourceKey = key,
            resourceType = type,
            labels = mapOf(
                TicketDockerResource.RUN_LABEL to runId,
                TicketDockerResource.PROFILE_LABEL to profileId,
                TicketDockerResource.RESOURCE_KEY_LABEL to key,
                TicketDockerResource.RESOURCE_TYPE_LABEL to type,
            ),
        )

    private fun createNetwork(resource: TicketDockerResource): Network {
        recordIntent(resource)
        cutpoint.hit(TicketTopologyCutpoint.Phase.BEFORE_CREATE, resource.resourceKey)
        val created = Network.builder()
            .createNetworkCmdModifier { it.withLabels(resource.labels) }
            .build()
        return recordCreated(resource, created.id, created, Network::close)
    }

    private fun createRedis(resource: TicketDockerResource, network: Network): RedisServer {
        recordIntent(resource)
        cutpoint.hit(TicketTopologyCutpoint.Phase.BEFORE_CREATE, resource.resourceKey)
        val created = RedisServer().apply {
            withNetwork(network)
            withNetworkAliases(REDIS_ALIAS)
            withLabels(resource.labels)
            waitingFor(NoReadinessWait)
        }
        return try {
            created.start()
            recordCreated(resource, created.containerId, created, RedisServer::stop)
        } catch (error: Throwable) {
            runCatching(created::stop)
            throw error
        }
    }

    private fun createToxiproxy(resource: TicketDockerResource, network: Network): ToxiproxyServer {
        recordIntent(resource)
        cutpoint.hit(TicketTopologyCutpoint.Phase.BEFORE_CREATE, resource.resourceKey)
        val created = ToxiproxyServer().apply {
            withNetwork(network)
            withLabels(resource.labels)
            waitingFor(NoReadinessWait)
        }
        return try {
            created.start()
            recordCreated(resource, created.containerId, created, ToxiproxyServer::stop)
        } catch (error: Throwable) {
            runCatching(created::stop)
            throw error
        }
    }

    private fun <T> recordCreated(
        resource: TicketDockerResource,
        dockerId: String,
        created: T,
        cleanup: (T) -> Unit,
    ): T =
        try {
            cutpoint.hit(TicketTopologyCutpoint.Phase.AFTER_CREATE, resource.resourceKey)
            journal.append(
                "DOCKER_RESOURCE_CREATED",
                resource.journalFields + ("dockerObjectId" to dockerId.requireNotBlank("dockerObjectId")),
            )
            created
        } catch (error: Throwable) {
            runCatching { cleanup(created) }
            throw error
        }

    private fun recordIntent(resource: TicketDockerResource) {
        journal.append("DOCKER_CREATE_INTENT", resource.journalFields)
    }

    private fun awaitClient(server: ToxiproxyServer): ToxiproxyClient {
        return TicketHighContentionAwait.value(
            timeout = READINESS_TIMEOUT,
            pollInterval = READINESS_POLL,
            description = "Toxiproxy control API did not become ready",
        ) {
            val client = ToxiproxyClient(server.host, server.controlPort)
            try {
                client.version()
                client
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun awaitRedis() {
        TicketHighContentionAwait.condition(
            timeout = READINESS_TIMEOUT,
            pollInterval = READINESS_POLL,
            description = "proxied Ticket Redis did not become ready",
        ) {
            openConnection().use {
                it.ping() == "PONG"
            }
        }
    }

    internal companion object {
        private const val REDIS_ALIAS = "ticket-redis-primary"
        private const val PROXY_NAME = "ticket-redis-proxy"
        private const val PROXY_PORT = 8666
        private const val RESET_TOXIC = "ticket-reset-existing"
        private val CLIENT_TIMEOUT = Duration.ofSeconds(2)
        private val READINESS_TIMEOUT = Duration.ofSeconds(10)
        private val READINESS_POLL = Duration.ofMillis(50)

        fun start(
            runId: String,
            profileId: String,
            journal: TicketHighContentionJournal,
            cutpoint: TicketTopologyCutpoint = TicketTopologyCutpoint.NONE,
        ): TicketProxiedTopology {
            val topology = TicketProxiedTopology(
                runId.requireNotBlank("runId"),
                profileId.requireNotBlank("profileId"),
                journal,
                cutpoint,
            )
            return try {
                topology.start()
                topology
            } catch (error: Throwable) {
                try {
                    topology.close()
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
                throw error
            }
        }
    }
}

internal data class TicketDockerResource(
    val resourceKey: String,
    val resourceType: String,
    val labels: Map<String, String>,
) {
    val journalFields: Map<String, String> = buildMap {
        put("resourceKey", resourceKey.requireNotBlank("resourceKey"))
        put("resourceType", resourceType.requireNotBlank("resourceType"))
        labels.toSortedMap().forEach { (key, value) ->
            put("label.$key", value.requireNotBlank("label value"))
        }
    }

    internal companion object {
        const val RUN_LABEL = "io.bluetape4k.high-contention.run-id"
        const val PROFILE_LABEL = "io.bluetape4k.high-contention.profile-id"
        const val RESOURCE_KEY_LABEL = "io.bluetape4k.high-contention.resource-key"
        const val RESOURCE_TYPE_LABEL = "io.bluetape4k.high-contention.resource-type"
    }
}

private object NoReadinessWait : WaitStrategy {
    override fun waitUntilReady(waitStrategyTarget: WaitStrategyTarget) = Unit

    override fun withStartupTimeout(startupTimeout: Duration): WaitStrategy = this
}

private class OwnedConnection(
    private val socket: Socket,
    private val onClosed: (OwnedConnection) -> Unit,
) : TicketRedisProbeConnection {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
    private val lock = ReentrantLock()
    private var closed = false

    override fun ping(): String = lock.withLock {
        check(!closed) { "Redis probe connection is closed" }
        writer.write("*1\r\n${'$'}4\r\nPING\r\n")
        writer.flush()
        val response = requireNotNull(reader.readLine()) { "Redis closed without a PING response" }
        check(response.startsWith("+")) { "Redis PING failed" }
        return response.removePrefix("+")
    }

    override fun close() {
        lock.withLock {
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
}
