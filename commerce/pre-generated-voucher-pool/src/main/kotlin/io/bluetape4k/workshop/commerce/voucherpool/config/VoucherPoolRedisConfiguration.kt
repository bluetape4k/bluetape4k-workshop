@file:Suppress("MagicNumber")

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.bucket4j.addBandwidth
import io.bluetape4k.bucket4j.bucketConfiguration
import io.bluetape4k.bucket4j.distributed.BucketProxyProvider
import io.bluetape4k.bucket4j.distributed.redis.lettuceBasedProxyManagerOf
import io.bluetape4k.bucket4j.ratelimit.RateLimitResult
import io.bluetape4k.bucket4j.ratelimit.RateLimiter
import io.bluetape4k.bucket4j.ratelimit.distributed.DistributedRateLimiter
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.redis.lettuce.filter.BloomFilterOptions
import io.bluetape4k.redis.lettuce.filter.LettuceBloomFilter
import io.bluetape4k.support.requireMatches
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
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
/**
 * VoucherPoolRedisProperties은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property enabled 프로모션 바우처 정책을 선택하는 플래그입니다. true/false별 처리 경로와 기본값을 계약으로 유지합니다.
 * @property uri 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property commandTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property failureThreshold 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property recoverySuccessThreshold 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property probeInterval 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property maxInFlightProbes 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property limits 처리량·할당량·크기 또는 진척도입니다. 음수와 상한을 검증하고 경쟁 요청에서 원자적으로 갱신합니다.
 * @property quotaPeriod 처리량·할당량·크기 또는 진척도입니다. 음수와 상한을 검증하고 경쟁 요청에서 원자적으로 갱신합니다.
 * @property bloomExpectedInsertions 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property bloomFalseProbability 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Suppress("LongParameterList")
internal class VoucherPoolRedisProperties(
    /**
     * `enabled`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val enabled: Boolean = false,
    /**
     * `uri`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val uri: String = "redis://127.0.0.1:6379",
    /**
     * `commandTimeout`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val commandTimeout: Duration = 500.millis(),
    /**
     * `failureThreshold`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val failureThreshold: Int = 1,
    /**
     * `recoverySuccessThreshold`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val recoverySuccessThreshold: Int = 3,
    /**
     * `probeInterval`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val probeInterval: Duration = 1.seconds(),
    /**
     * `maxInFlightProbes`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val maxInFlightProbes: Int = 1,
    /**
     * `limits`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val limits: AdmissionLimits = AdmissionLimits.defaults(),
    /**
     * `quotaPeriod`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val quotaPeriod: Duration = 1.minutes(),
    /**
     * `bloomExpectedInsertions`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val bloomExpectedInsertions: Long = 100_000,
    /**
     * `bloomFalseProbability`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val bloomFalseProbability: Double = 0.01,
) {
    init {
        uri.requireNotBlank { "Redis URI must not be blank" }
        commandTimeout.requirePositiveDuration("commandTimeout")
        quotaPeriod.requirePositiveDuration("quotaPeriod")
        bloomExpectedInsertions.requirePositiveNumber("bloomExpectedInsertions")
        require(bloomFalseProbability > 0.0 && bloomFalseProbability < 1.0) {
            "Bloom false probability must be between zero and one"
        }
    }
}

/** optional Lettuce, Bloom, leader resource를 advisory infrastructure로 소유합니다. */
/** optional Lettuce, Bloom, leader resource를 advisory infrastructure로 소유합니다. */
internal class VoucherPoolRedisResources private constructor(
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
     * `health`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val health: VoucherPoolHealthState?,
    /**
     * `metrics`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: VoucherPoolMetrics?,
): AutoCloseable {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val leaderLock = ReentrantLock()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val closed = AtomicBoolean()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    @Volatile
    private var leaderConnection: StatefulRedisConnection<String, String>? = null
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    @Volatile
    private var leaderElector: LettuceLeaderElector? = null

    /**
     * leaderElector은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun leaderElector(): LettuceLeaderElector? = leaderLock.withLock {
        leaderElector
            ?: try {
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

    /**
     * close은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
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
        log.info { "voucher_pool_redis_resources_closed" }
    }

    companion object: KLogging() {
        /**
         * open은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param health 업무 처리에 필요한 health 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param metrics 업무 처리에 필요한 metrics 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        fun open(
            properties: VoucherPoolRedisProperties,
            health: VoucherPoolHealthState? = null,
            metrics: VoucherPoolMetrics? = null,
        ): VoucherPoolRedisResources {
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
                health?.recover(VoucherPoolHealthComponent.REDIS)
                log.info { "voucher_pool_redis_connected" }
            } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
                health?.degrade(VoucherPoolHealthComponent.REDIS, VoucherPoolHealthReason.REDIS_UNAVAILABLE)
                metrics?.degraded(VoucherPoolHealthComponent.REDIS)
                filter?.close() ?: connection?.close()
                connection = null
                filter = null
                log.warn {
                    "voucher_pool_redis_unavailable fallback=NODE_LOCAL failure=${failure.javaClass.simpleName}"
                }
            }
            return VoucherPoolRedisResources(client, connection, filter, health, metrics)
        }
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */

        private const val BLOOM_FILTER_NAME = "voucher-pool:risk:v1"
    }
}

/**
 * voucherPoolDistributedAdmissionBackend은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency
 * key·revision·감사 필드를 함께 보존합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param client 업무 처리에 필요한 client 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun voucherPoolDistributedAdmissionBackend(
    client: RedisClient,
    properties: VoucherPoolRedisProperties,
): VoucherPoolAdmissionBackend {
    val limiters = AdmissionNamespace.entries.associateWith { namespace ->
        val manager = lettuceBasedProxyManagerOf(client) {}
        val configuration = bucketConfiguration {
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

    return VoucherPoolAdmissionBackend { namespace, key ->
        limiters.getValue(namespace).consume(key, 1)
    }
}
/**
 * RecoverableVoucherPoolAdmissionBackend은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property client 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property properties 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property health 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property metrics 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class RecoverableVoucherPoolAdmissionBackend(
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
    private val properties: VoucherPoolRedisProperties,
    /**
     * `health`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val health: VoucherPoolHealthState? = null,
    /**
     * `metrics`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: VoucherPoolMetrics? = null,
): VoucherPoolAdmissionBackend {

    companion object: KLogging()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val delegateLock = ReentrantLock()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    @Volatile
    private var delegate: VoucherPoolAdmissionBackend? = null

    /**
     * consume은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param namespace 업무 처리에 필요한 namespace 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    override fun consume(namespace: AdmissionNamespace, key: String): RateLimitResult =
        try {
            (delegate ?: createDelegate()).consume(namespace, key)
        } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
            delegate = null
            health?.degrade(VoucherPoolHealthComponent.REDIS, VoucherPoolHealthReason.REDIS_UNAVAILABLE)
            metrics?.degraded(VoucherPoolHealthComponent.REDIS)
            RateLimitResult.error(failure)
        }

    /**
     * createDelegate은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun createDelegate(): VoucherPoolAdmissionBackend =
        delegateLock.withLock {
            delegate ?: voucherPoolDistributedAdmissionBackend(client, properties)
                .also {
                    delegate = it
                    health?.recover(VoucherPoolHealthComponent.REDIS)
                }
        }
}

/**
 * 이 node가 advisory leader slot을 소유할 때만 기존 Task 8 worker action을 실행합니다.
 * @property electorProvider 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property instanceId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
 * @property health 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property metrics 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal class VoucherPoolLeaderTrigger(
    /**
     * `electorProvider`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val electorProvider: () -> LettuceLeaderElector?,
    /**
     * `instanceId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    private val instanceId: String,
    /**
     * `health`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val health: VoucherPoolHealthState? = null,
    /**
     * `metrics`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: VoucherPoolMetrics? = null,
) {
    companion object: KLogging() {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val LOCK_NAME = Regex("[a-z0-9-]{1,48}")
    }

    /**
     * run은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lockName 업무 처리에 필요한 lockName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun <T> run(lockName: String, action: () -> T): LeaderRunResult<T>? {
        lockName.requireMatches(LOCK_NAME, "lockName")

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

    /**
     * degrade은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun degrade() {
        health?.degrade(VoucherPoolHealthComponent.LEADER, VoucherPoolHealthReason.LEADER_UNAVAILABLE)
        metrics?.degraded(VoucherPoolHealthComponent.LEADER)
    }
}

/**
 * scheduled trigger와 manual trigger를 같은 durable Task 8 worker entry point로 라우팅합니다.
 * @property workers 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property runtime 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
 * @property leader 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal class VoucherPoolWorkerTrigger(
    /**
     * `workers`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val workers: VoucherPoolWorkers,
    /**
     * `runtime`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val runtime: VoucherPoolRuntimeControl,
    /**
     * `leader`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val leader: VoucherPoolLeaderTrigger? = null,
) {
    /**
     * runScheduled은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun runScheduled(request: WorkerRunRequest): WorkerRunOutcome? {
        if (!runtime.triggersRunning() || !runtime.claimsAccepted()) return null
        val lockName = "voucher-pool-${request.kind.name.lowercase()}"

        return when (val leaderResult = leader?.run(lockName) { runShared(request) }) {
            null                       -> runShared(request)
            is LeaderRunResult.Elected -> leaderResult.value
            LeaderRunResult.Skipped    -> null
            is LeaderRunResult.ActionFailed -> null
        }
    }

    /**
     * runManual은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun runManual(request: WorkerRunRequest): WorkerRunOutcome = runShared(request)

    /**
     * runShared은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun runShared(request: WorkerRunRequest): WorkerRunOutcome {
        if (!runtime.claimsAccepted()) {
            return WorkerRunOutcome(
                state = WorkerRunState.NOT_ACQUIRED,
                processed = 0,
                effects = 0,
                claim = null,
                reasonCode = "SHUTTING_DOWN"
            )
        }

        return runtime.withClaim {
            workers.run(request) {
                runtime.claimsAccepted() && !Thread.currentThread().isInterrupted
            }
        } ?: WorkerRunOutcome(
            state = WorkerRunState.NOT_ACQUIRED,
            processed = 0,
            effects = 0,
            claim = null,
            reasonCode = "SHUTTING_DOWN"
        )
    }
}

@Configuration(proxyBeanMethods = false)
/**
 * VoucherPoolAdmissionConfiguration은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을
 * 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class VoucherPoolAdmissionConfiguration {

    companion object: KLogging()

    /**
     * voucherPoolAdmissionGate은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param backend 업무 처리에 필요한 backend 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param clocks 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param health 업무 처리에 필요한 health 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
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
            recoveryPolicy = AdmissionRecoveryPolicy(
                failureThreshold = properties.redis.failureThreshold,
                recoverySuccessThreshold = properties.redis.recoverySuccessThreshold,
                probeInterval = properties.redis.probeInterval,
                maxInFlightProbes = properties.redis.maxInFlightProbes,
            ),
            clock = clocks.getIfAvailable(Clock::systemUTC),
            stateObserver = { state ->
                when {
                    !properties.redis.enabled       -> health.recover(VoucherPoolHealthComponent.REDIS)
                    state == AdmissionState.HEALTHY -> {
                        health.recover(VoucherPoolHealthComponent.REDIS)
                        health.recover(VoucherPoolHealthComponent.RECOVERY)
                    }
                    state == AdmissionState.DEGRADED -> {
                        health.recover(VoucherPoolHealthComponent.RECOVERY)
                        health.degrade(VoucherPoolHealthComponent.REDIS, VoucherPoolHealthReason.REDIS_UNAVAILABLE)
                    }
                    else                            -> {
                        health.recover(VoucherPoolHealthComponent.REDIS)
                        health.recovering()
                    }
                }
            },
        )
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "workshop.voucher-pool.redis", name = ["enabled"], havingValue = "true")
/**
 * VoucherPoolRedisConfiguration은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class VoucherPoolRedisConfiguration {

    companion object: KLogging()

    /**
     * voucherPoolRedisResources은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param health 업무 처리에 필요한 health 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param metrics 업무 처리에 필요한 metrics 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean(destroyMethod = "close")
    fun voucherPoolRedisResources(
        properties: VoucherPoolProperties,
        health: VoucherPoolHealthState,
        metrics: VoucherPoolMetrics,
    ): VoucherPoolRedisResources = VoucherPoolRedisResources.open(properties.redis, health, metrics)

    /**
     * voucherPoolAdmissionBackend은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency
     * key·revision·감사 필드를
     * 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param resources 업무 처리에 필요한 resources 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param health 업무 처리에 필요한 health 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param metrics 업무 처리에 필요한 metrics 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean("voucherPoolAdmissionBackend")
    fun voucherPoolAdmissionBackend(
        resources: VoucherPoolRedisResources,
        properties: VoucherPoolProperties,
        health: VoucherPoolHealthState,
        metrics: VoucherPoolMetrics,
    ): VoucherPoolAdmissionBackend =
        RecoverableVoucherPoolAdmissionBackend(resources.client, properties.redis, health, metrics)

    /**
     * voucherPoolLeaderTrigger은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param resources 업무 처리에 필요한 resources 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param health 업무 처리에 필요한 health 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param metrics 업무 처리에 필요한 metrics 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
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
