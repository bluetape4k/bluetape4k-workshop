package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.bucket4j.addBandwidth
import io.bluetape4k.bucket4j.bucketConfiguration
import io.bluetape4k.bucket4j.distributed.BucketProxyProvider
import io.bluetape4k.bucket4j.distributed.redis.lettuceBasedProxyManagerOf
import io.bluetape4k.bucket4j.ratelimit.RateLimitResult
import io.bluetape4k.bucket4j.ratelimit.RateLimiter
import io.bluetape4k.bucket4j.ratelimit.distributed.DistributedRateLimiter
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.redis.lettuce.filter.BloomFilterOptions
import io.bluetape4k.redis.lettuce.filter.LettuceBloomFilter
import io.bluetape4k.support.toUtf8Bytes
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** optional Redis resource를 소유하되, 부재가 PostgreSQL command를 막지 못하게 합니다. */
/** optional Redis resource를 소유하되, 부재가 PostgreSQL command를 막지 못하게 합니다. */
internal class VoucherRedisResources private constructor(
    /**
     * `client`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val client: RedisClient,
    /**
     * `bloomConnection`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val bloomConnection: StatefulRedisConnection<String, String>?,
    /**
     * `bloomFilter`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val bloomFilter: LettuceBloomFilter?,
    /**
     * `degradation`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val degradation: VoucherDegradationState,
): AutoCloseable {

    companion object: KLogging() {
        /**
         * open은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        fun open(
            properties: VoucherRedisProperties,
            degradation: VoucherDegradationState = VoucherDegradationState(),
        ): VoucherRedisResources {
            /**
             * `uri`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
             *
             * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
             */
            val uri = RedisURI.create(properties.uri).apply { timeout = properties.commandTimeout }
            /**
             * `client`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
             *
             * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
             */
            val client = LettuceClients.clientOf(uri)
            /**
             * `connection`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            var connection: StatefulRedisConnection<String, String>? = null
            /**
             * `filter`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            var filter: LettuceBloomFilter? = null
            try {
                connection = client.connect()
                filter = LettuceBloomFilter(
                    connection,
                    filterName = BLOOM_FILTER_NAME,
                    options =
                        BloomFilterOptions(
                            properties.bloomExpectedInsertions,
                            properties.bloomFalseProbability,
                        ),
                ).also { it.tryInit() }
                degradation.recover(VoucherDegradedComponent.REDIS)
                log.info { "voucher_redis_bloom_connected" }
            } catch (failure: Exception) {
                degradation.degrade(VoucherDegradedComponent.REDIS)
                filter?.close() ?: connection?.close()
                connection = null
                filter = null
                log.warn { "voucher_redis_bloom_unavailable fallback=POSTGRES failure=${failure.javaClass.simpleName}" }
            }
            return VoucherRedisResources(client, connection, filter, degradation)
        }

        /**
         * `BLOOM_FILTER_NAME`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
         *
         * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
         */
        private const val BLOOM_FILTER_NAME = "voucher:risk:v1"
    }

    /**
     * `leaderLock`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val leaderLock = ReentrantLock()
    /**
     * `closed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val closed = AtomicBoolean()

    @Volatile
    /**
     * `leaderConnection`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private var leaderConnection: StatefulRedisConnection<String, String>? = null

    @Volatile
    /**
     * `leaderElector`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private var leaderElector: LettuceLeaderElector? = null

    /** lazy하게 연결되는 Lettuce elector 하나를 재사용하고, startup backend가 없으면 이후 재시도합니다. */
    /** lazy하게 연결되는 Lettuce elector 하나를 재사용하고, startup backend가 없으면 이후 재시도합니다. */
    fun leaderElector(): LettuceLeaderElector? = leaderLock.withLock {
        leaderElector ?: try {
            val connection = client.connect()
            leaderConnection = connection
            LettuceLeaderElector(connection).also {
                leaderElector = it
                degradation.recover(VoucherDegradedComponent.LEADER)
                log.info { "voucher_redis_leader_connected" }
            }
        } catch (failure: Exception) {
            degradation.degrade(VoucherDegradedComponent.LEADER)
            log.warn {
                "voucher_redis_leader_unavailable fallback=MANUAL failure=${failure.javaClass.simpleName}"
            }
            null
        }
    }

    /**
     * close은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        leaderConnection?.close()

        if (bloomFilter != null) {
            bloomFilter.close()
        } else {
            bloomConnection?.close()
        }
        LettuceClients.shutdown(client)
        log.info { "voucher_redis_resources_closed" }
    }
}

/**
 * voucherDistributedRateLimiter은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal fun voucherDistributedRateLimiter(
    client: RedisClient,
    properties: VoucherRedisProperties,
): RateLimiter<String> {
    val manager = lettuceBasedProxyManagerOf(client) {}
    val configuration = bucketConfiguration {
        addBandwidth {
            Bandwidth.builder()
                .capacity(properties.quotaCapacity)
                .refillGreedy(properties.quotaCapacity, properties.quotaPeriod)
                .build()
        }
    }
    return DistributedRateLimiter(
        BucketProxyProvider(
            proxyManager = manager,
            bucketConfiguration = configuration,
            keyPrefix = "voucher:rate:"
        ),
    )
}

/** boot 시점 Redis outage가 in-place로 복구될 수 있도록 Bucket4j adapter를 lazy하게 재생성합니다. */
/** boot 시점 Redis outage가 in-place로 복구될 수 있도록 Bucket4j adapter를 lazy하게 재생성합니다. */
internal class RecoverableVoucherRateLimiter(
    /**
     * `client`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val client: RedisClient,
    /**
     * `properties`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: VoucherRedisProperties,
    /**
     * `degradation`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val degradation: VoucherDegradationState = VoucherDegradationState(),
): RateLimiter<String> {

    companion object: KLogging()

    /**
     * `delegateLock`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val delegateLock = ReentrantLock()

    @Volatile
    /**
     * `delegate`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private var delegate: RateLimiter<String>? = null

    /**
     * consume은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun consume(
        key: String,
        numToken: Long,
    ): RateLimitResult {
        val limiter = delegate ?: createDelegate()
        ?: return RateLimitResult.error(IllegalStateException("redis unavailable"))

        return limiter.consume(key, numToken)
    }

    /**
     * createDelegate은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun createDelegate(): RateLimiter<String>? = delegateLock.withLock {
        delegate
            ?: try {
                voucherDistributedRateLimiter(client, properties)
                    .also {
                        delegate = it
                        degradation.recover(VoucherDegradedComponent.REDIS)
                    }
            } catch (failure: Exception) {
                degradation.degrade(VoucherDegradedComponent.REDIS)
                log.warn {
                    "voucher_redis_rate_limiter_unavailable fallback=POSTGRES failure=${failure.javaClass.simpleName}"
                }
                null
            }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VoucherProperties::class)
/**
 * VoucherAdmissionConfiguration은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class VoucherAdmissionConfiguration {

    companion object: KLogging()

    @Bean
    /**
     * voucherAdmissionKeyFactory은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param properties 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun voucherAdmissionKeyFactory(properties: VoucherProperties): VoucherAdmissionKeyFactory =
        VoucherAdmissionKeyFactory(
            version = properties.keys.currentVersion,
            rateKey = properties.keys.redisSlot.toUtf8Bytes(),
            riskKey = properties.keys.risk.toUtf8Bytes(),
        )

    @Bean
    /**
     * voucherAdmissionGate은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
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
    /**
     * riskSignalService은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun riskSignalService(
        keys: VoucherAdmissionKeyFactory,
        resources: ObjectProvider<VoucherRedisResources>,
        degradation: ObjectProvider<VoucherDegradationState>,
    ): RiskSignalService =
        RiskSignalService(
            keys = keys,
            backend = resources.ifAvailable?.bloomFilter?.let(::LettuceBloomRiskBackend),
            degradation = degradation.getIfAvailable(::VoucherDegradationState),
        )
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "workshop.voucher.redis", name = ["enabled"], havingValue = "true")
/**
 * VoucherRedisConfiguration은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class VoucherRedisConfiguration {

    companion object: KLogging()

    @Bean(destroyMethod = "close")
    /**
     * voucherRedisResources은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun voucherRedisResources(
        properties: VoucherProperties,
        degradation: ObjectProvider<VoucherDegradationState>,
    ): VoucherRedisResources =
        VoucherRedisResources.open(
            properties = properties.redis,
            degradation = degradation.getIfAvailable(::VoucherDegradationState)
        )

    @Bean("voucherRateLimiter")
    /**
     * voucherRateLimiter은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun voucherRateLimiter(
        resources: VoucherRedisResources,
        properties: VoucherProperties,
        degradation: ObjectProvider<VoucherDegradationState>,
    ): RateLimiter<String> =
        RecoverableVoucherRateLimiter(
            client = resources.client,
            properties = properties.redis,
            degradation = degradation.getIfAvailable(::VoucherDegradationState),
        )
}
