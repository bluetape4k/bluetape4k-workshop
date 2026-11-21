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

/**
 * HighContentionDockerResource 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class HighContentionDockerResource(
    resourceKey: String,
    resourceType: String,
    labels: Map<String, String>,
) {
/**
 * resourceKey은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val resourceKey: String =
        HighContentionArtifactPaths.requireIdentifier(resourceKey, "resourceKey")
/**
 * resourceType은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val resourceType: String =
        HighContentionArtifactPaths.requireIdentifier(resourceType, "resourceType")
/**
 * labels은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val labels: Map<String, String> = labels.toSortedMap()
/**
 * journalFields은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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
/**
 * RUN_ID_LABEL은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val RUN_ID_LABEL = "io.bluetape4k.high-contention.run-id"
/**
 * PROFILE_ID_LABEL은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val PROFILE_ID_LABEL = "io.bluetape4k.high-contention.profile-id"
/**
 * RESOURCE_KEY_LABEL은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val RESOURCE_KEY_LABEL = "io.bluetape4k.high-contention.resource-key"
/**
 * RESOURCE_TYPE_LABEL은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val RESOURCE_TYPE_LABEL = "io.bluetape4k.high-contention.resource-type"

/**
 * REQUIRED_LABEL_KEYS은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val REQUIRED_LABEL_KEYS: Set<String> = setOf(
            RUN_ID_LABEL,
            PROFILE_ID_LABEL,
            RESOURCE_KEY_LABEL,
            RESOURCE_TYPE_LABEL,
        )
    }
}

/**
 * JobConsoleDockerResources 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class JobConsoleDockerResources(
/**
 * network은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val network: HighContentionDockerResource,
/**
 * redis은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val redis: HighContentionDockerResource,
/**
 * toxiproxy은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val toxiproxy: HighContentionDockerResource,
) {
/**
 * all은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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
/**
 * RUN_PROFILE_LABEL_KEYS은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val RUN_PROFILE_LABEL_KEYS = listOf(
            HighContentionDockerResource.RUN_ID_LABEL,
            HighContentionDockerResource.PROFILE_ID_LABEL,
        )
    }
}

/**
 * JobConsoleRedisConnection 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
interface JobConsoleRedisConnection : AutoCloseable {
/**
 * ping은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun ping(): String
}

/**
 * JobConsoleProxiedTopology 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class JobConsoleProxiedTopology private constructor(
/**
 * journal은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val journal: HighContentionJournal,
/**
 * resources은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val resources: JobConsoleDockerResources,
/**
 * cutpoint은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val cutpoint: JobConsoleTopologyCutpoint,
) : AutoCloseable {

/**
 * network은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var network: Network? = null
/**
 * redis은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var redis: RedisServer? = null
/**
 * toxiproxy은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var toxiproxy: ToxiproxyServer? = null
/**
 * proxy은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var proxy: Proxy? = null
/**
 * connectionReset은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var connectionReset: Toxic? = null
/**
 * connections은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val connections = linkedSetOf<OwnedRedisConnection>()
/**
 * closed은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var closed = false

/**
 * redisUri은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    lateinit var redisUri: String
        private set

    @Synchronized
/**
 * openRedisConnection은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
/**
 * cutExistingConnections은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
/**
 * restoreExistingConnections은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun restoreExistingConnections() {
        connectionReset?.remove()
        connectionReset = null
    }

    @Synchronized
/**
 * disableNewConnections은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun disableNewConnections() {
        check(!closed) { "proxied topology is closed" }
        proxy().disable()
    }

    @Synchronized
/**
 * enableNewConnections은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun enableNewConnections() {
        check(!closed) { "proxied topology is closed" }
        proxy().enable()
    }

/**
 * routesThroughProxy은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    internal fun routesThroughProxy(): Boolean {
        val proxyServer = toxiproxy ?: return false
        val proxyUri = "redis://${proxyServer.host}:${proxyServer.getMappedPort(PROXY_PORT)}"
        val directRedis = redis ?: return false
        return redisUri == proxyUri &&
            redisUri != "redis://${directRedis.host}:${directRedis.port}"
    }

/**
 * close은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * start은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * awaitToxiproxyClient은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun awaitToxiproxyClient(server: ToxiproxyServer): ToxiproxyClient {
        return HighContentionAwait.value(
            timeout = READINESS_TIMEOUT,
            pollInterval = READINESS_POLL_INTERVAL,
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

/**
 * awaitProxiedRedis은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun awaitProxiedRedis() {
        HighContentionAwait.condition(
            timeout = READINESS_TIMEOUT,
            pollInterval = READINESS_POLL_INTERVAL,
            description = "proxied Redis path did not become ready",
        ) {
            openRedisConnection().use {
                it.ping() == "PONG"
            }
        }
    }

/**
 * createNetwork은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * createRedis은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun createRedis(
        resource: HighContentionDockerResource,
        network: Network,
    ): RedisServer {
        recordCreateIntent(resource)
        cutpoint.hit(JobConsoleTopologyCutpoint.Phase.BEFORE_CREATE, resource.resourceKey)
/**
 * created은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * createToxiproxy은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun createToxiproxy(
        resource: HighContentionDockerResource,
        network: Network,
    ): ToxiproxyServer {
        recordCreateIntent(resource)
        cutpoint.hit(JobConsoleTopologyCutpoint.Phase.BEFORE_CREATE, resource.resourceKey)
/**
 * created은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * recordCreateIntent은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun recordCreateIntent(resource: HighContentionDockerResource) {
        journal.append(
            HighContentionJournalEvent.DOCKER_CREATE_INTENT,
            resource.journalFields,
        )
    }

/**
 * recordCreated은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun recordCreated(
        resource: HighContentionDockerResource,
        dockerObjectId: String,
    ) {
        journal.append(
            HighContentionJournalEvent.DOCKER_RESOURCE_CREATED,
            resource.journalFields + ("dockerObjectId" to dockerObjectId.requireNotBlank("dockerObjectId")),
        )
    }

/**
 * network은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun network(): Network = checkNotNull(network) { "network is not allocated" }

/**
 * toxiproxy은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun toxiproxy(): ToxiproxyServer =
        checkNotNull(toxiproxy) { "Toxiproxy is not allocated" }

/**
 * proxy은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun proxy(): Proxy = checkNotNull(proxy) { "Redis proxy is not allocated" }

    companion object {
/**
 * REDIS_NETWORK_ALIAS은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val REDIS_NETWORK_ALIAS = "redis-primary"
/**
 * PROXY_NAME은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val PROXY_NAME = "redis-primary-proxy"
/**
 * PROXY_PORT은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val PROXY_PORT = 8666
/**
 * CONNECTION_RESET_TOXIC은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val CONNECTION_RESET_TOXIC = "reset-existing-connections"
/**
 * CLIENT_TIMEOUT은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val CLIENT_TIMEOUT = Duration.ofSeconds(2)
/**
 * READINESS_TIMEOUT은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val READINESS_TIMEOUT = Duration.ofSeconds(10)
/**
 * READINESS_POLL_INTERVAL은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val READINESS_POLL_INTERVAL = Duration.ofMillis(50)

/**
 * start은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun start(
            journal: HighContentionJournal,
            resources: JobConsoleDockerResources,
        ): JobConsoleProxiedTopology =
            start(journal, resources, JobConsoleTopologyCutpoint.NONE)

/**
 * start은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        internal fun start(
            journal: HighContentionJournal,
            resources: JobConsoleDockerResources,
            cutpoint: JobConsoleTopologyCutpoint,
        ): JobConsoleProxiedTopology {
/**
 * topology은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * JobConsoleTopologyCutpoint 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal fun interface JobConsoleTopologyCutpoint {
/**
 * hit은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun hit(
        phase: Phase,
        resourceKey: String,
    )

    /**
     * `Phase`은 진행 단계입니다.
     * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
     */
    enum class Phase {
        /**
         * `Phase.BEFORE_CREATE`은 `before create` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한
         * 뒤 사용합니다.
         */
        BEFORE_CREATE,
        /**
         * `Phase.AFTER_CREATE`은 `after create` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤
         * 사용합니다.
         */
        AFTER_CREATE,
    }

    companion object {
/**
 * NONE은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val NONE = JobConsoleTopologyCutpoint { _, _ -> }

/**
 * beforeCreate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun beforeCreate(resourceKey: String): JobConsoleTopologyCutpoint =
            throwingAt(Phase.BEFORE_CREATE, resourceKey)

/**
 * afterCreate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun afterCreate(resourceKey: String): JobConsoleTopologyCutpoint =
            throwingAt(Phase.AFTER_CREATE, resourceKey)

/**
 * throwingAt은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * ExpectedCutpointException 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class ExpectedCutpointException : IllegalStateException("expected topology crash cutpoint")

/**
 * NoReadinessWait 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private object NoReadinessWait : WaitStrategy {
/**
 * waitUntilReady은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun waitUntilReady(waitStrategyTarget: WaitStrategyTarget) = Unit

/**
 * withStartupTimeout은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun withStartupTimeout(startupTimeout: Duration): WaitStrategy = this
}

/**
 * OwnedRedisConnection 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class OwnedRedisConnection(
/**
 * socket은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val socket: Socket,
/**
 * onClosed은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val onClosed: (OwnedRedisConnection) -> Unit,
) : JobConsoleRedisConnection {
/**
 * reader은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
/**
 * writer은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
/**
 * closed은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var closed = false

    @Synchronized
/**
 * ping은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun ping(): String {
        check(!closed) { "Redis connection is closed" }
        writer.write("*1\r\n${'$'}4\r\nPING\r\n")
        writer.flush()
        val response = reader.readLine() ?: error("Redis connection closed without a PING response")
        check(response.startsWith("+")) { "Redis PING failed" }
        return response.removePrefix("+")
    }

    @Synchronized
/**
 * close은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
