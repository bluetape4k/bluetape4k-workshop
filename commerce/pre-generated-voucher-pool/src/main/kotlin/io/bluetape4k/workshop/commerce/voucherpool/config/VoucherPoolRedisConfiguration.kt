@file:Suppress("MagicNumber")

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.bucket4j.addBandwidth
import io.bluetape4k.bucket4j.bucketConfiguration
import io.bluetape4k.bucket4j.distributed.BucketProxyProvider
import io.bluetape4k.bucket4j.distributed.redis.lettuceBasedProxyManagerOf
import io.bluetape4k.bucket4j.ratelimit.RateLimiter
import io.bluetape4k.bucket4j.ratelimit.RateLimitResult
import io.bluetape4k.bucket4j.ratelimit.distributed.DistributedRateLimiter
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.redis.lettuce.filter.BloomFilterOptions
import io.bluetape4k.redis.lettuce.filter.LettuceBloomFilter
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionLimits
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionNamespace
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionRecoveryPolicy
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionState
import io.bluetape4k.workshop.commerce.voucherpool.admission.VoucherPoolAdmissionBackend
import io.bluetape4k.workshop.commerce.voucherpool.admission.VoucherPoolAdmissionGate
import io.bluetape4k.workshop.commerce.voucherpool.worker.VoucherPoolWorkers
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerRunOutcome
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerRunRequest
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerRunState
import io.github.bucket4j.Bandwidth
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Suppress("LongParameterList")
internal class VoucherPoolRedisProperties(
    val enabled: Boolean = false,
    val uri: String = "redis://127.0.0.1:6379",
    val commandTimeout: Duration = Duration.ofMillis(500),
    val failureThreshold: Int = 1,
    val recoverySuccessThreshold: Int = 3,
    val probeInterval: Duration = Duration.ofSeconds(1),
    val maxInFlightProbes: Int = 1,
    val limits: AdmissionLimits = AdmissionLimits.defaults(),
    val quotaPeriod: Duration = Duration.ofMinutes(1),
    val bloomExpectedInsertions: Long = 100_000,
    val bloomFalseProbability: Double = 0.01,
) {
    init {
        require(uri.isNotBlank()) { "Redis URI must not be blank" }
        require(!commandTimeout.isNegative && !commandTimeout.isZero) { "Redis command timeout must be positive" }
        require(!quotaPeriod.isNegative && !quotaPeriod.isZero) { "Redis quota period must be positive" }
        require(bloomExpectedInsertions > 0) { "Bloom expected insertions must be positive" }
        require(bloomFalseProbability > 0.0 && bloomFalseProbability < 1.0) {
            "Bloom false probability must be between zero and one"
        }
    }
}

/** optional Lettuce, Bloom, leader resource를 advisory infrastructure로 소유합니다. */
internal class VoucherPoolRedisResources private constructor(
    val client: RedisClient,
    private val bloomConnection: StatefulRedisConnection<String, String>?,
    val bloomFilter: LettuceBloomFilter?,
    private val health: VoucherPoolHealthState?,
    private val metrics: VoucherPoolMetrics?,
) : AutoCloseable {
    private val leaderLock = ReentrantLock()
    private val closed = AtomicBoolean()

    @Volatile
    private var leaderConnection: StatefulRedisConnection<String, String>? = null

    @Volatile
    private var leaderElector: LettuceLeaderElector? = null

    fun leaderElector(): LettuceLeaderElector? =
        leaderLock.withLock {
            leaderElector ?: try {
                val connection = client.connect()
                leaderConnection = connection
                LettuceLeaderElector(connection).also {
                    leaderElector = it
                    health?.recover(VoucherPoolHealthComponent.LEADER)
                    log.info { "voucher_pool_leader_connected" }
                }
            } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
                health?.degrade(VoucherPoolHealthComponent.LEADER, VoucherPoolHealthReason.LEADER_UNAVAILABLE)
                metrics?.degraded(VoucherPoolHealthComponent.LEADER)
                log.warn { "voucher_pool_leader_unavailable fallback=MANUAL failure=${failure.javaClass.simpleName}" }
                null
            }
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        leaderConnection?.close()
        if (bloomFilter != null) {
            bloomFilter.close()
        } else {
            bloomConnection?.close()
        }
        LettuceClients.shutdown(client)
        log.info { "voucher_pool_redis_resources_closed" }
    }

    companion object : KLogging() {
        fun open(
            properties: VoucherPoolRedisProperties,
            health: VoucherPoolHealthState? = null,
            metrics: VoucherPoolMetrics? = null,
        ): VoucherPoolRedisResources {
            val uri = RedisURI.create(properties.uri).apply { timeout = properties.commandTimeout }
            val client = LettuceClients.clientOf(uri)
            var connection: StatefulRedisConnection<String, String>? = null
            var filter: LettuceBloomFilter? = null
            try {
                connection = client.connect()
                filter =
                    LettuceBloomFilter(
                        connection,
                        filterName = BLOOM_FILTER_NAME,
                        options =
                            BloomFilterOptions(
                                properties.bloomExpectedInsertions,
                                properties.bloomFalseProbability,
                            ),
                    ).also { it.tryInit() }
                health?.recover(VoucherPoolHealthComponent.REDIS)
                log.info { "voucher_pool_redis_connected" }
            } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
                health?.degrade(VoucherPoolHealthComponent.REDIS, VoucherPoolHealthReason.REDIS_UNAVAILABLE)
                metrics?.degraded(VoucherPoolHealthComponent.REDIS)
                filter?.close() ?: connection?.close()
                connection = null
                filter = null
                log.warn {
                    "voucher_pool_redis_unavailable fallback=NODE_LOCAL " +
                        "failure=${failure.javaClass.simpleName}"
                }
            }
            return VoucherPoolRedisResources(client, connection, filter, health, metrics)
        }

        private const val BLOOM_FILTER_NAME = "voucher-pool:risk:v1"
    }
}

internal fun voucherPoolDistributedAdmissionBackend(
    client: RedisClient,
    properties: VoucherPoolRedisProperties,
): VoucherPoolAdmissionBackend {
    val limiters =
        AdmissionNamespace.entries.associateWith { namespace ->
            val manager = lettuceBasedProxyManagerOf(client) {}
            val configuration =
                bucketConfiguration {
                    addBandwidth {
                        Bandwidth.builder()
                            .capacity(properties.limits[namespace].toLong())
                            .refillGreedy(properties.limits[namespace].toLong(), properties.quotaPeriod)
                            .build()
                    }
                }
            DistributedRateLimiter(
                BucketProxyProvider(
                    manager,
                    configuration,
                    keyPrefix = "voucher-pool:admission:${namespace.name.lowercase()}:",
                ),
            ) as RateLimiter<String>
        }
    return VoucherPoolAdmissionBackend { namespace, key -> limiters.getValue(namespace).consume(key, 1) }
}

internal class RecoverableVoucherPoolAdmissionBackend(
    private val client: RedisClient,
    private val properties: VoucherPoolRedisProperties,
    private val health: VoucherPoolHealthState? = null,
    private val metrics: VoucherPoolMetrics? = null,
) : VoucherPoolAdmissionBackend {
    private val delegateLock = ReentrantLock()

    @Volatile
    private var delegate: VoucherPoolAdmissionBackend? = null

    override fun consume(namespace: AdmissionNamespace, key: String): RateLimitResult =
        try {
            (delegate ?: createDelegate()).consume(namespace, key)
        } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
            delegate = null
            health?.degrade(VoucherPoolHealthComponent.REDIS, VoucherPoolHealthReason.REDIS_UNAVAILABLE)
            metrics?.degraded(VoucherPoolHealthComponent.REDIS)
            RateLimitResult.error(failure)
        }

    private fun createDelegate(): VoucherPoolAdmissionBackend =
        delegateLock.withLock {
            delegate ?: voucherPoolDistributedAdmissionBackend(client, properties).also {
                delegate = it
                health?.recover(VoucherPoolHealthComponent.REDIS)
            }
        }
}

/** 이 node가 advisory leader slot을 소유할 때만 기존 Task 8 worker action을 실행합니다. */
internal class VoucherPoolLeaderTrigger(
    private val electorProvider: () -> LettuceLeaderElector?,
    private val instanceId: String,
    private val health: VoucherPoolHealthState? = null,
    private val metrics: VoucherPoolMetrics? = null,
) {
    fun <T> run(lockName: String, action: () -> T): LeaderRunResult<T>? {
        require(lockName.matches(LOCK_NAME)) { "leader lock name must be bounded" }
        val elector = electorProvider()
        if (elector == null) {
            degrade()
            return null
        }
        return try {
            elector.runIfLeaderResult(LeaderSlot(lockName, instanceId), action).also {
                health?.recover(VoucherPoolHealthComponent.LEADER)
            }
        } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
            degrade()
            log.warn { "voucher_pool_leader_trigger_failed failure=${failure.javaClass.simpleName}" }
            null
        }
    }

    private fun degrade() {
        health?.degrade(VoucherPoolHealthComponent.LEADER, VoucherPoolHealthReason.LEADER_UNAVAILABLE)
        metrics?.degraded(VoucherPoolHealthComponent.LEADER)
    }

    companion object : KLogging() {
        private val LOCK_NAME = Regex("[a-z0-9-]{1,48}")
    }
}

/** scheduled trigger와 manual trigger를 같은 durable Task 8 worker entry point로 라우팅합니다. */
internal class VoucherPoolWorkerTrigger(
    private val workers: VoucherPoolWorkers,
    private val runtime: VoucherPoolRuntimeControl,
    private val leader: VoucherPoolLeaderTrigger? = null,
) {
    fun runScheduled(request: WorkerRunRequest): WorkerRunOutcome? {
        if (!runtime.triggersRunning() || !runtime.claimsAccepted()) return null
        val lockName = "voucher-pool-${request.kind.name.lowercase()}"
        return when (val leaderResult = leader?.run(lockName) { runShared(request) }) {
            null -> runShared(request)
            is LeaderRunResult.Elected -> leaderResult.value
            LeaderRunResult.Skipped -> null
            is LeaderRunResult.ActionFailed -> null
        }
    }

    fun runManual(request: WorkerRunRequest): WorkerRunOutcome = runShared(request)

    private fun runShared(request: WorkerRunRequest): WorkerRunOutcome {
        if (!runtime.claimsAccepted()) {
            return WorkerRunOutcome(WorkerRunState.NOT_ACQUIRED, 0, 0, null, "SHUTTING_DOWN")
        }
        return runtime.withClaim {
            workers.run(request) {
                runtime.claimsAccepted() && !Thread.currentThread().isInterrupted
            }
        } ?: WorkerRunOutcome(WorkerRunState.NOT_ACQUIRED, 0, 0, null, "SHUTTING_DOWN")
    }
}

@Configuration(proxyBeanMethods = false)
internal class VoucherPoolAdmissionConfiguration {
    @Bean
    fun voucherPoolAdmissionGate(
        @Qualifier("voucherPoolAdmissionBackend") backend: ObjectProvider<VoucherPoolAdmissionBackend>,
        properties: VoucherPoolProperties,
        clocks: ObjectProvider<Clock>,
        health: VoucherPoolHealthState,
    ): VoucherPoolAdmissionGate =
        VoucherPoolAdmissionGate(
            backend = backend.ifAvailable,
            limits = properties.redis.limits,
            recoveryPolicy =
                AdmissionRecoveryPolicy(
                    failureThreshold = properties.redis.failureThreshold,
                    recoverySuccessThreshold = properties.redis.recoverySuccessThreshold,
                    probeInterval = properties.redis.probeInterval,
                    maxInFlightProbes = properties.redis.maxInFlightProbes,
                ),
            clock = clocks.getIfAvailable(Clock::systemUTC),
            stateObserver = { state ->
                when {
                    !properties.redis.enabled -> health.recover(VoucherPoolHealthComponent.REDIS)
                    state == AdmissionState.HEALTHY -> {
                        health.recover(VoucherPoolHealthComponent.REDIS)
                        health.recover(VoucherPoolHealthComponent.RECOVERY)
                    }
                    state == AdmissionState.DEGRADED -> {
                        health.recover(VoucherPoolHealthComponent.RECOVERY)
                        health.degrade(VoucherPoolHealthComponent.REDIS, VoucherPoolHealthReason.REDIS_UNAVAILABLE)
                    }
                    else -> {
                        health.recover(VoucherPoolHealthComponent.REDIS)
                        health.recovering()
                    }
                }
            },
        )
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "workshop.voucher-pool.redis", name = ["enabled"], havingValue = "true")
internal class VoucherPoolRedisConfiguration {
    @Bean(destroyMethod = "close")
    fun voucherPoolRedisResources(
        properties: VoucherPoolProperties,
        health: VoucherPoolHealthState,
        metrics: VoucherPoolMetrics,
    ): VoucherPoolRedisResources = VoucherPoolRedisResources.open(properties.redis, health, metrics)

    @Bean("voucherPoolAdmissionBackend")
    fun voucherPoolAdmissionBackend(
        resources: VoucherPoolRedisResources,
        properties: VoucherPoolProperties,
        health: VoucherPoolHealthState,
        metrics: VoucherPoolMetrics,
    ): VoucherPoolAdmissionBackend =
        RecoverableVoucherPoolAdmissionBackend(resources.client, properties.redis, health, metrics)

    @Bean
    fun voucherPoolLeaderTrigger(
        resources: VoucherPoolRedisResources,
        properties: VoucherPoolProperties,
        health: VoucherPoolHealthState,
        metrics: VoucherPoolMetrics,
    ): VoucherPoolLeaderTrigger =
        VoucherPoolLeaderTrigger(
            electorProvider = resources::leaderElector,
            instanceId = properties.workerInstanceId,
            health = health,
            metrics = metrics,
        )
}
