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
 * Integration tests for [ResilientProductService].
 *
 * Uses [ToxiproxyServer] to inject network failures into the Redis connection,
 * verifying the full CircuitBreaker state machine:
 *
 * ```
 * CLOSED ──(failures ≥ threshold)──► OPEN ──(waitDuration elapsed)──► HALF-OPEN
 *                                                                            │
 *                                    CLOSED ◄──(probe succeeded)────────────┤
 *                                    OPEN   ◄──(probe failed)───────────────┘
 * ```
 *
 * ## Test scenarios
 * 1. **Happy path** — Redis available, all reads and writes succeed via CircuitBreaker (CLOSED).
 * 2. **Failure injection** — `timeout(1ms)` toxic causes fast connection drop; circuit breaker
 *    transitions to OPEN; subsequent reads fall back to Caffeine.
 * 3. **Recovery** — toxic removed; circuit transitions HALF-OPEN → CLOSED; Redis resumes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResilientCacheServiceTest {

    companion object : KLoggingChannel() {
        /** Name of the timeout toxic used to inject connection failures. */
        private const val TIMEOUT_TOXIC = "drop-connections"
        /** Probe attempts allowed in HALF-OPEN before closing/re-opening. */
        private const val HALF_OPEN_PERMITTED = 2
        /** Minimum calls before failure-rate is evaluated. */
        private const val MIN_CALLS = 4
        /** Sliding window used to compute failure rate. */
        private const val WINDOW_SIZE = 4
        /** Seconds the CB stays OPEN before transitioning to HALF-OPEN. */
        private const val WAIT_OPEN_SECONDS = 5L
    }

    // ── Containers ────────────────────────────────────────────────────────────

    private lateinit var network: Network
    private lateinit var redis: RedisServer
    private lateinit var toxiproxyServer: ToxiproxyServer
    private lateinit var proxy: Proxy
    private var proxyPort: Int = 0

    // ── Service components ────────────────────────────────────────────────────

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

        // Create proxy: listens on 0.0.0.0:8666 inside Docker, forwarding to redis:6379
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
        // Remove timeout toxic from previous test (idempotent)
        runCatching { proxy.toxics().get(TIMEOUT_TOXIC).remove() }
        // Force CB back to CLOSED and reset metrics
        circuitBreaker.transitionToClosedState()
        // Flush Redis to avoid cross-test data bleed
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

    // ── Test 2: Failure injection — circuit opens, Caffeine fallback ──────────

    @Test
    fun `failure injection - circuit opens after Redis failures, fallback to Caffeine`() = runSuspendIO {
        val id = "product-002"
        val cachedValue = "Bluetape4k Workshop"

        // Pre-populate local cache so fallback has data
        service.putProduct(id, cachedValue)
        log.debug { "Pre-populated caches with product[$id] = $cachedValue" }

        // Verify Redis works with CB CLOSED
        service.getProduct(id) shouldBeEqualTo cachedValue
        circuitBreaker.state shouldBeEqualTo CircuitBreaker.State.CLOSED

        // Inject timeout(1ms) toxic: connections are dropped after 1 ms → fast failure
        proxy.toxics().timeout(TIMEOUT_TOXIC, ToxicDirection.UPSTREAM, 1)
        log.info { "Injected timeout(1ms) toxic — connections now fail fast" }

        // Trigger enough failures to open the circuit
        repeat(MIN_CALLS) { i ->
            recordRedisRead(id).also { result ->
                log.debug { "Call $i: ${if (result.isSuccess) "success=${result.getOrNull()}" else result.exceptionOrNull()?.javaClass?.simpleName}" }
            }
        }

        // Circuit must now be OPEN
        circuitBreaker.state shouldBeEqualTo CircuitBreaker.State.OPEN
        log.info { "Circuit breaker OPEN. Metrics: ${circuitBreaker.metrics.failureRate}% failure rate" }

        // service.getProduct with CB OPEN → falls back to Caffeine
        val fallbackResult = service.getProduct(id)
        fallbackResult shouldBeEqualTo cachedValue
        log.info { "Fallback result from Caffeine: $fallbackResult" }

        circuitBreaker.state shouldBeEqualTo CircuitBreaker.State.OPEN
    }

    // ── Test 3: Recovery — toxic removed, circuit transitions CLOSED ──────────

    @Test
    fun `recovery - remove toxic, circuit transitions HALF-OPEN then CLOSED`() = runSuspendIO {
        val id = "product-003"
        val value = "Spring Boot Advanced"

        // Store in both caches first
        service.putProduct(id, value)

        // Open the circuit by injecting timeout toxic
        proxy.toxics().timeout(TIMEOUT_TOXIC, ToxicDirection.UPSTREAM, 1)
        repeat(MIN_CALLS) {
            recordRedisRead(id)
        }
        circuitBreaker.state shouldBeEqualTo CircuitBreaker.State.OPEN
        log.info { "Circuit OPEN — removing toxic to simulate Redis recovery" }

        // Remove the toxic so Redis becomes healthy again
        proxy.toxics().get(TIMEOUT_TOXIC).remove()
        log.info { "Timeout toxic removed — Redis connections healthy" }

        // Wait for CB to auto-transition to HALF-OPEN (waitDurationInOpenState = 5 s)
        await atMost Duration.ofSeconds(WAIT_OPEN_SECONDS + 3) untilAsserted {
            val state = circuitBreaker.state
            log.debug { "Waiting for HALF-OPEN/CLOSED, current: $state" }
            (state == CircuitBreaker.State.HALF_OPEN || state == CircuitBreaker.State.CLOSED).shouldBeTrue()
        }
        log.info { "Circuit transitioned to ${circuitBreaker.state}" }

        // Probe calls succeed → circuit closes
        repeat(HALF_OPEN_PERMITTED) {
            service.getProduct(id)
        }

        // Circuit must now be CLOSED again
        await atMost Duration.ofSeconds(3) untilAsserted {
            circuitBreaker.state shouldBeEqualTo CircuitBreaker.State.CLOSED
        }
        log.info { "Circuit recovered to CLOSED — Redis reads active again" }

        // Verify Redis read works normally
        val recovered = service.getProduct(id)
        recovered shouldBeEqualTo value
        log.info { "Recovered read from Redis: $recovered" }
    }

    // ── Test 4: Cache miss — neither Redis nor Caffeine holds the value ───────

    @Test
    fun `cache miss returns null when value not in either cache`() = runSuspendIO {
        val result = service.getProduct("nonexistent-999")
        result.shouldBeNull()
        log.debug { "Cache miss correctly returned null" }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildService() {
        val connectionConfig = RedisStandaloneConfiguration(toxiproxyServer.host, proxyPort)
        // Short command timeout so failure detection is fast (avoids 60s default waits per call)
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
