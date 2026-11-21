package io.bluetape4k.workshop.commerce.ticket.highcontention

import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import eu.rekawek.toxiproxy.model.Toxic
import eu.rekawek.toxiproxy.model.ToxicDirection
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.support.checkNull
import io.bluetape4k.support.checkStartsWith
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
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

/**
 * TicketRedisProbeConnection 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal interface TicketRedisProbeConnection: AutoCloseable {
/**
 * ping은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun ping(): String
}

/**
 * TicketTopologyCutpoint 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal fun interface TicketTopologyCutpoint {
/**
 * hit은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun hit(phase: Phase, resourceKey: String)

    /**
     * `Phase`은 진행 단계입니다.
     * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
     */
    enum class Phase {
        /**
         * `Phase.BEFORE_CREATE`은 생성 side effect보다 먼저 정책·입력 검증을 수행하는 단계입니다.
         */
        BEFORE_CREATE,
        /**
         * `Phase.AFTER_CREATE`은 생성 commit 뒤 후속 이벤트·감사·projection을 처리하는 단계입니다.
         */
        AFTER_CREATE,
    }

    companion object {
/**
 * NONE은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val NONE = TicketTopologyCutpoint { _, _ -> }
    }
}

/**
 * TicketProxiedTopology 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketProxiedTopology private constructor(
/**
 * runId은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val runId: String,
/**
 * profileId은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val profileId: String,
/**
 * journal은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val journal: TicketHighContentionJournal,
/**
 * cutpoint은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val cutpoint: TicketTopologyCutpoint,
): AutoCloseable {
/**
 * network은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var network: Network? = null
/**
 * redis은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var redis: RedisServer? = null
/**
 * toxiproxy은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var toxiproxy: ToxiproxyServer? = null
/**
 * proxy은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var proxy: Proxy? = null
/**
 * reset은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var reset: Toxic? = null
/**
 * connections은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val connections = linkedSetOf<OwnedConnection>()
/**
 * stateLock은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val stateLock = ReentrantLock()
/**
 * closed은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var closed = false

/**
 * redisUri은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    lateinit var redisUri: String
        private set

/**
 * routesOnlyThroughProxy은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun routesOnlyThroughProxy(): Boolean {
        val redisServer = redis ?: return false
        val proxyServer = toxiproxy ?: return false

        return redisUri == "redis://${proxyServer.host}:${proxyServer.getMappedPort(PROXY_PORT)}" &&
                redisUri != "redis://${redisServer.host}:${redisServer.port}"
    }

/**
 * openConnection은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun openConnection(): TicketRedisProbeConnection = stateLock.withLock {
        check(!closed) { "Ticket topology is closed" }
        check(::redisUri.isInitialized) { "Ticket topology is not ready" }
/**
 * endpoint은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val endpoint = URI.create(redisUri)
/**
 * socket은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * cutExistingConnections은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun cutExistingConnections() = stateLock.withLock {
        reset.checkNull { "existing connection cut is already active" }

        reset = proxy.requireNotNull("proxy")
            .toxics()
            .resetPeer(
                RESET_TOXIC,
                ToxicDirection.DOWNSTREAM,
                0,
            )
    }

/**
 * disableNewConnections은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun disableNewConnections() = stateLock.withLock {
        proxy.requireNotNull("proxy").disable()
    }

/**
 * recover은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun recover() = stateLock.withLock {
        reset?.remove()
        reset = null
        proxy.requireNotNull("proxy").enable()
    }

/**
 * close은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * start은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun start() {
        network = createNetwork(resource("network", "network"))
        redis = createRedis(resource("redis", "container"), network.requireNotNull("network"))
        toxiproxy = createToxiproxy(resource("toxiproxy", "container"), network.requireNotNull("network"))
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

/**
 * resource은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * createNetwork은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun createNetwork(resource: TicketDockerResource): Network {
        recordIntent(resource)
        cutpoint.hit(TicketTopologyCutpoint.Phase.BEFORE_CREATE, resource.resourceKey)
        val created = Network.builder()
            .createNetworkCmdModifier { it.withLabels(resource.labels) }
            .build()
        return recordCreated(resource, created.id, created, Network::close)
    }

/**
 * createRedis은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * createToxiproxy은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * recordCreated은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * recordIntent은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun recordIntent(resource: TicketDockerResource) {
        journal.append("DOCKER_CREATE_INTENT", resource.journalFields)
    }

/**
 * awaitClient은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * awaitRedis은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
/**
 * REDIS_ALIAS은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val REDIS_ALIAS = "ticket-redis-primary"
/**
 * PROXY_NAME은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val PROXY_NAME = "ticket-redis-proxy"
/**
 * PROXY_PORT은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val PROXY_PORT = 8666
/**
 * RESET_TOXIC은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val RESET_TOXIC = "ticket-reset-existing"
/**
 * CLIENT_TIMEOUT은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val CLIENT_TIMEOUT = 2.seconds()
/**
 * READINESS_TIMEOUT은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val READINESS_TIMEOUT = 10.seconds()
/**
 * READINESS_POLL은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val READINESS_POLL = 50.millis()

/**
 * start은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun start(
            runId: String,
            profileId: String,
            journal: TicketHighContentionJournal,
            cutpoint: TicketTopologyCutpoint = TicketTopologyCutpoint.NONE,
        ): TicketProxiedTopology {
/**
 * topology은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * TicketDockerResource 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal data class TicketDockerResource(
/**
 * resourceKey은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val resourceKey: String,
/**
 * resourceType은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val resourceType: String,
/**
 * labels은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val labels: Map<String, String>,
) {
/**
 * journalFields은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val journalFields: Map<String, String> = buildMap {
        put("resourceKey", resourceKey.requireNotBlank("resourceKey"))
        put("resourceType", resourceType.requireNotBlank("resourceType"))
        labels.toSortedMap().forEach { (key, value) ->
            put("label.$key", value.requireNotBlank("label value"))
        }
    }

    internal companion object {
/**
 * RUN_LABEL은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val RUN_LABEL = "io.bluetape4k.high-contention.run-id"
/**
 * PROFILE_LABEL은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val PROFILE_LABEL = "io.bluetape4k.high-contention.profile-id"
/**
 * RESOURCE_KEY_LABEL은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val RESOURCE_KEY_LABEL = "io.bluetape4k.high-contention.resource-key"
/**
 * RESOURCE_TYPE_LABEL은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val RESOURCE_TYPE_LABEL = "io.bluetape4k.high-contention.resource-type"
    }
}

/**
 * NoReadinessWait 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private object NoReadinessWait: WaitStrategy {
/**
 * waitUntilReady은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun waitUntilReady(waitStrategyTarget: WaitStrategyTarget) = Unit

/**
 * withStartupTimeout은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun withStartupTimeout(startupTimeout: Duration): WaitStrategy = this
}

/**
 * OwnedConnection 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class OwnedConnection(
/**
 * socket은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val socket: Socket,
/**
 * onClosed은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val onClosed: (OwnedConnection) -> Unit,
): TicketRedisProbeConnection {
/**
 * reader은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
/**
 * writer은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
/**
 * lock은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val lock = ReentrantLock()
/**
 * closed은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var closed = false

/**
 * ping은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun ping(): String = lock.withLock {
        check(!closed) { "Redis probe connection is closed" }
        writer.write("*1\r\n${'$'}4\r\nPING\r\n")
        writer.flush()
/**
 * response은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = reader.readLine().requireNotNull { "Redis closed without a PING response" }
        response.checkStartsWith("+") { "Redis PING failed" }
        return response.removePrefix("+")
    }

/**
 * close은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
