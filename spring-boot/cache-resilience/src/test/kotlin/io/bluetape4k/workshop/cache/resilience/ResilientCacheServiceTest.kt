package io.bluetape4k.workshop.cache.resilience

import com.github.benmanes.caffeine.cache.Caffeine
import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import eu.rekawek.toxiproxy.model.ToxicDirection
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.resilience4j.SuspendDecorators
import io.bluetape4k.testcontainers.infra.ToxiproxyServer
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.cache.resilience.service.ResilientProductService
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.testcontainers.containers.Network
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/**
 * [ResilientProductService] integration test 입니다.
 *
 * [ToxiproxyServer] 로 Redis connection 에 network failure 를 주입해
 * 전체 CircuitBreaker state machine 을 검증합니다.
 *
 * ```
 * CLOSED ──(failures ≥ threshold)──► OPEN ──(waitDuration elapsed)──► HALF-OPEN
 *                                                                            │
 *                                    CLOSED ◄──(probe succeeded)────────────┤
 *                                    OPEN   ◄──(probe failed)───────────────┘
 * ```
 *
 * ## Test scenarios
 * 1. **Happy path** — Redis 가 사용 가능하며 모든 read/write 가 CircuitBreaker(CLOSED)를 통해 성공합니다.
 * 2. **Failure injection** — `timeout(1ms)` toxic 으로 connection 을 빠르게 끊습니다.
 *    circuit breaker 는 OPEN 으로 전환되고 후속 read 는 Caffeine 으로 fallback 합니다.
 * 3. **Recovery** — toxic 제거 후 circuit 이 HALF-OPEN -> CLOSED 로 전환되고 Redis 가 재개됩니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResilientCacheServiceTest {

    companion object : KLoggingChannel() {
        /** connection failure 를 주입하는 timeout toxic 이름입니다. */
        private const val TIMEOUT_TOXIC = "drop-connections"
        /** HALF-OPEN 에서 close/re-open 전 허용되는 probe 시도 수입니다. */
        private const val HALF_OPEN_PERMITTED = 2
        /** failure-rate 평가 전 필요한 최소 call 수입니다. */
        private const val MIN_CALLS = 4
        /** failure rate 계산에 사용하는 sliding window 입니다. */
        private const val WINDOW_SIZE = 4
        /** CB 가 HALF-OPEN 으로 전환되기 전 OPEN 에 머무는 초 단위 시간입니다. */
        private const val WAIT_OPEN_SECONDS = 5L
    }

    // ── Container ─────────────────────────────────────────────────────────────

    private lateinit var network: Network
    private lateinit var redis: RedisServer
    private lateinit var toxiproxyServer: ToxiproxyServer
    private lateinit var proxy: Proxy
    private var proxyPort: Int = 0

    // ── Service component ─────────────────────────────────────────────────────

    private lateinit var factory: LettuceConnectionFactory
    private lateinit var redisTemplate: RedisTemplate<String, String>
    private lateinit var circuitBreaker: CircuitBreaker
    private lateinit var service: ResilientProductService

    @BeforeAll
    fun startInfrastructure() {
        network = Network.newNetwork()

        redis = RedisServer().also {
            it.withNetwork(network)
            it.withNetworkAliases("redis")
        }
        redis.start()
        log.info { "Redis started at ${redis.host}:${redis.getMappedPort(RedisServer.PORT)}" }

        toxiproxyServer = ToxiproxyServer().also {
            it.withNetwork(network)
        }
        toxiproxyServer.start()
        log.info { "Toxiproxy started at ${toxiproxyServer.host}:${toxiproxyServer.controlPort}" }

        // proxy 를 생성합니다. Docker 내부 0.0.0.0:8666 에서 listen 하고 redis:6379 로 forwarding 합니다.
        val client = ToxiproxyClient(toxiproxyServer.host, toxiproxyServer.controlPort)
        proxy = client.createProxy(
            "redis-primary",
            "0.0.0.0:8666",
            "redis:${RedisServer.PORT}",
        )
        proxyPort = toxiproxyServer.getMappedPort(8666)
        log.info { "Toxiproxy proxy mapped to ${toxiproxyServer.host}:$proxyPort" }

        buildService()
    }

    @AfterAll
    fun stopInfrastructure() {
        runCatching { factory.destroy() }
        runCatching { toxiproxyServer.stop() }
        runCatching { redis.stop() }
        runCatching { network.close() }
    }

    @BeforeEach
    fun resetState() {
        // 이전 test 의 timeout toxic 을 제거합니다(idempotent).
        runCatching { proxy.toxics().get(TIMEOUT_TOXIC).remove() }
        // CB 를 CLOSED 로 강제 복귀시키고 metrics 를 reset 합니다.
        circuitBreaker.transitionToClosedState()
        // test 간 data bleed 를 피하려고 Redis 를 flush 합니다.
        runCatching { redisTemplate.connectionFactory?.connection?.serverCommands()?.flushAll() }
    }

    // ── Test 1: Happy path ────────────────────────────────────────────────────

    @Test
    fun `happy path - Redis available, reads and writes succeed`() = runSuspendIO {
        val id = "product-001"
        val value = "Kotlin Coroutines Book"

        service.putProduct(id, value)
        log.debug { "Put product[$id] = $value" }

        val result = service.getProduct(id)
        result shouldBeEqualTo value
        log.debug { "Got product[$id] = $result" }

        circuitBreaker.state shouldBeEqualTo CircuitBreaker.State.CLOSED
        log.info { "CB state after happy path: ${circuitBreaker.state}" }
    }

    // ── Test 2: Failure injection — circuit open 후 Caffeine fallback ─────────

    @Test
    fun `failure injection - circuit opens after Redis failures, fallback to Caffeine`() = runSuspendIO {
        val id = "product-002"
        val cachedValue = "Bluetape4k Workshop"

        // fallback 에 사용할 data 가 있도록 local cache 를 미리 채웁니다.
        service.putProduct(id, cachedValue)
        log.debug { "Pre-populated caches with product[$id] = $cachedValue" }

        // CB CLOSED 상태에서 Redis 가 동작하는지 검증합니다.
        service.getProduct(id) shouldBeEqualTo cachedValue
        circuitBreaker.state shouldBeEqualTo CircuitBreaker.State.CLOSED

        // timeout(1ms) toxic 을 주입합니다. connection 이 1 ms 뒤 끊겨 빠르게 실패합니다.
        proxy.toxics().timeout(TIMEOUT_TOXIC, ToxicDirection.UPSTREAM, 1)
        log.info { "Injected timeout(1ms) toxic — connections now fail fast" }

        // circuit 을 open 하기에 충분한 failure 를 발생시킵니다.
        repeat(MIN_CALLS) { i ->
            recordRedisRead(id).also { result ->
                log.debug { "Call $i: ${if (result.isSuccess) "success=${result.getOrNull()}" else result.exceptionOrNull()?.javaClass?.simpleName}" }
            }
        }

        // 이제 circuit 은 OPEN 이어야 합니다.
        circuitBreaker.state shouldBeEqualTo CircuitBreaker.State.OPEN
        log.info { "Circuit breaker OPEN. Metrics: ${circuitBreaker.metrics.failureRate}% failure rate" }

        // CB OPEN 상태의 service.getProduct 는 Caffeine 으로 fallback 합니다.
        val fallbackResult = service.getProduct(id)
        fallbackResult shouldBeEqualTo cachedValue
        log.info { "Fallback result from Caffeine: $fallbackResult" }

        circuitBreaker.state shouldBeEqualTo CircuitBreaker.State.OPEN
    }

    // ── Test 3: Recovery — toxic 제거 후 circuit 이 CLOSED 로 전환 ─────────────

    @Test
    fun `recovery - remove toxic, circuit transitions HALF-OPEN then CLOSED`() = runSuspendIO {
        val id = "product-003"
        val value = "Spring Boot Advanced"

        // 먼저 두 cache 에 모두 저장합니다.
        service.putProduct(id, value)

        // timeout toxic 을 주입해 circuit 을 open 합니다.
        proxy.toxics().timeout(TIMEOUT_TOXIC, ToxicDirection.UPSTREAM, 1)
        repeat(MIN_CALLS) {
            recordRedisRead(id)
        }
        circuitBreaker.state shouldBeEqualTo CircuitBreaker.State.OPEN
        log.info { "Circuit OPEN — removing toxic to simulate Redis recovery" }

        // Redis 가 다시 healthy 해지도록 toxic 을 제거합니다.
        proxy.toxics().get(TIMEOUT_TOXIC).remove()
        log.info { "Timeout toxic removed — Redis connections healthy" }

        // CB 가 HALF-OPEN 으로 자동 전환될 때까지 기다립니다(waitDurationInOpenState = 5초).
        await atMost Duration.ofSeconds(WAIT_OPEN_SECONDS + 3) untilAsserted {
            val state = circuitBreaker.state
            log.debug { "Waiting for HALF-OPEN/CLOSED, current: $state" }
            (state == CircuitBreaker.State.HALF_OPEN || state == CircuitBreaker.State.CLOSED).shouldBeTrue()
        }
        log.info { "Circuit transitioned to ${circuitBreaker.state}" }

        // probe call 이 성공하면 circuit 이 닫힙니다.
        repeat(HALF_OPEN_PERMITTED) {
            service.getProduct(id)
        }

        // 이제 circuit 은 다시 CLOSED 이어야 합니다.
        await atMost Duration.ofSeconds(3) untilAsserted {
            circuitBreaker.state shouldBeEqualTo CircuitBreaker.State.CLOSED
        }
        log.info { "Circuit recovered to CLOSED — Redis reads active again" }

        // Redis read 가 정상 동작하는지 검증합니다.
        val recovered = service.getProduct(id)
        recovered shouldBeEqualTo value
        log.info { "Recovered read from Redis: $recovered" }
    }

    // ── Test 4: Cache miss — Redis 와 Caffeine 모두 값이 없음 ─────────────────

    @Test
    fun `cache miss returns null when value not in either cache`() = runSuspendIO {
        val result = service.getProduct("nonexistent-999")
        result.shouldBeNull()
        log.debug { "Cache miss correctly returned null" }
    }

    // ── Private helper ────────────────────────────────────────────────────────

    private fun buildService() {
        val connectionConfig = RedisStandaloneConfiguration(toxiproxyServer.host, proxyPort)
        // failure detection 이 빠르도록 command timeout 을 짧게 둡니다(call 당 기본 60초 대기 방지).
        val clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(3))
            .build()
        factory = LettuceConnectionFactory(connectionConfig, clientConfig)
        factory.afterPropertiesSet()

        redisTemplate = RedisTemplate<String, String>().apply {
            setConnectionFactory(factory)
            keySerializer = StringRedisSerializer.UTF_8
            valueSerializer = StringRedisSerializer.UTF_8
            afterPropertiesSet()
        }

        val localCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build<String, String>()

        val cbConfig = CircuitBreakerConfig.custom()
            .slidingWindowSize(WINDOW_SIZE)
            .minimumNumberOfCalls(MIN_CALLS)
            .failureRateThreshold(100f)           // open after 100% failures in window
            .waitDurationInOpenState(Duration.ofSeconds(WAIT_OPEN_SECONDS))
            .permittedNumberOfCallsInHalfOpenState(HALF_OPEN_PERMITTED)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .recordExceptions(Exception::class.java)
            .build()

        circuitBreaker = CircuitBreaker.of("redis-test-cb", cbConfig)

        circuitBreaker.eventPublisher
            .onStateTransition { e ->
                log.info { "[CB] state transition: ${e.stateTransition}" }
            }

        service = ResilientProductService(redisTemplate, localCache, circuitBreaker)
        log.info { "ResilientProductService built with proxy ${toxiproxyServer.host}:$proxyPort" }
    }

    private suspend fun recordRedisRead(id: String): Result<String?> {
        return try {
            Result.success(
                SuspendDecorators.ofSupplier<String?> {
                    redisTemplate.opsForValue().get("product:$id")
                }
                    .withCircuitBreaker(circuitBreaker)
                    .invoke()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
