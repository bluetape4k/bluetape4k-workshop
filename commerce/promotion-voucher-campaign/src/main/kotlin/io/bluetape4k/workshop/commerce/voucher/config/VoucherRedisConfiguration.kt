package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.bucket4j.addBandwidth
import io.bluetape4k.bucket4j.bucketConfiguration
import io.bluetape4k.bucket4j.distributed.BucketProxyProvider
import io.bluetape4k.bucket4j.distributed.redis.lettuceBasedProxyManagerOf
import io.bluetape4k.bucket4j.ratelimit.RateLimiter
import io.bluetape4k.bucket4j.ratelimit.RateLimitResult
import io.bluetape4k.bucket4j.ratelimit.distributed.DistributedRateLimiter
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.redis.lettuce.filter.BloomFilterOptions
import io.bluetape4k.redis.lettuce.filter.LettuceBloomFilter
import io.bluetape4k.workshop.commerce.voucher.admission.AdmissionRecoveryPolicy
import io.bluetape4k.workshop.commerce.voucher.admission.LettuceBloomRiskBackend
import io.bluetape4k.workshop.commerce.voucher.admission.RiskSignalService
import io.bluetape4k.workshop.commerce.voucher.admission.VoucherAdmissionGate
import io.bluetape4k.workshop.commerce.voucher.admission.VoucherAdmissionKeyFactory
import io.github.bucket4j.Bandwidth
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Owns optional Redis resources without allowing their absence to block PostgreSQL commands. */
internal class VoucherRedisResources private constructor(
    val client: RedisClient,
    private val bloomConnection: StatefulRedisConnection<String, String>?,
    val bloomFilter: LettuceBloomFilter?,
) : AutoCloseable {
    private val leaderLock = ReentrantLock()

    @Volatile
    private var leaderConnection: StatefulRedisConnection<String, String>? = null

    @Volatile
    private var leaderElector: LettuceLeaderElector? = null

    /** Reuses one lazily connected Lettuce elector and retries after an unavailable startup backend. */
    fun leaderElector(): LettuceLeaderElector? =
        leaderLock.withLock {
            leaderElector ?: try {
                val connection = client.connect()
                leaderConnection = connection
                LettuceLeaderElector(connection).also {
                    leaderElector = it
                    log.info { "voucher_redis_leader_connected" }
                }
            } catch (failure: Exception) {
                log.warn {
                    "voucher_redis_leader_unavailable fallback=MANUAL failure=${failure.javaClass.simpleName}"
                }
                null
            }
        }

    override fun close() {
        leaderConnection?.close()
        if (bloomFilter != null) {
            bloomFilter.close()
        } else {
            bloomConnection?.close()
        }
        LettuceClients.shutdown(client)
        log.info { "voucher_redis_resources_closed" }
    }

    companion object : KLogging() {
        fun open(properties: VoucherRedisProperties): VoucherRedisResources {
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
                log.info { "voucher_redis_bloom_connected" }
            } catch (failure: Exception) {
                filter?.close() ?: connection?.close()
                connection = null
                filter = null
                log.warn { "voucher_redis_bloom_unavailable fallback=POSTGRES failure=${failure.javaClass.simpleName}" }
            }
            return VoucherRedisResources(client, connection, filter)
        }

        private const val BLOOM_FILTER_NAME = "voucher:risk:v1"
    }
}

internal fun voucherDistributedRateLimiter(
    client: RedisClient,
    properties: VoucherRedisProperties,
): RateLimiter<String> {
    val manager = lettuceBasedProxyManagerOf(client) {}
    val configuration =
        bucketConfiguration {
            addBandwidth {
                Bandwidth.builder()
                    .capacity(properties.quotaCapacity)
                    .refillGreedy(properties.quotaCapacity, properties.quotaPeriod)
                    .build()
            }
        }
    return DistributedRateLimiter(
        BucketProxyProvider(manager, configuration, keyPrefix = "voucher:rate:"),
    )
}

/** Lazily recreates the Bucket4j adapter so a boot-time Redis outage can recover in-place. */
internal class RecoverableVoucherRateLimiter(
    private val client: RedisClient,
    private val properties: VoucherRedisProperties,
) : RateLimiter<String> {
    private val delegateLock = ReentrantLock()

    @Volatile
    private var delegate: RateLimiter<String>? = null

    override fun consume(
        key: String,
        numToken: Long,
    ): RateLimitResult {
        val limiter = delegate ?: createDelegate() ?: return RateLimitResult.error(IllegalStateException("redis unavailable"))
        return limiter.consume(key, numToken)
    }

    private fun createDelegate(): RateLimiter<String>? =
        delegateLock.withLock {
            delegate ?: try {
                voucherDistributedRateLimiter(client, properties).also { delegate = it }
            } catch (failure: Exception) {
                log.warn {
                    "voucher_redis_rate_limiter_unavailable fallback=POSTGRES failure=${failure.javaClass.simpleName}"
                }
                null
            }
        }

    companion object : KLogging()
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VoucherProperties::class)
internal class VoucherAdmissionConfiguration {
    @Bean
    fun voucherAdmissionKeyFactory(properties: VoucherProperties): VoucherAdmissionKeyFactory =
        VoucherAdmissionKeyFactory(
            version = properties.keys.currentVersion,
            rateKey = properties.keys.redisSlot.toByteArray(),
            riskKey = properties.keys.risk.toByteArray(),
        )

    @Bean
    fun voucherAdmissionGate(
        @Qualifier("voucherRateLimiter") rateLimiter: ObjectProvider<RateLimiter<String>>,
        properties: VoucherProperties,
        clock: Clock,
    ): VoucherAdmissionGate =
        VoucherAdmissionGate(
            rateLimiter = rateLimiter.ifAvailable,
            recoveryPolicy =
                AdmissionRecoveryPolicy(
                    failureThreshold = properties.redis.failureThreshold,
                    recoverySuccessThreshold = properties.redis.recoverySuccessThreshold,
                    probeInterval = properties.redis.probeInterval,
                    maxInFlightProbes = properties.redis.maxInFlightProbes,
                ),
            clock = clock,
        )

    @Bean
    fun riskSignalService(
        keys: VoucherAdmissionKeyFactory,
        resources: ObjectProvider<VoucherRedisResources>,
    ): RiskSignalService =
        RiskSignalService(
            keys,
            resources.ifAvailable?.bloomFilter?.let(::LettuceBloomRiskBackend),
        )
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "workshop.voucher.redis", name = ["enabled"], havingValue = "true")
internal class VoucherRedisConfiguration {
    @Bean(destroyMethod = "close")
    fun voucherRedisResources(properties: VoucherProperties): VoucherRedisResources =
        VoucherRedisResources.open(properties.redis)

    @Bean("voucherRateLimiter")
    fun voucherRateLimiter(
        resources: VoucherRedisResources,
        properties: VoucherProperties,
    ): RateLimiter<String> = RecoverableVoucherRateLimiter(resources.client, properties.redis)
}
