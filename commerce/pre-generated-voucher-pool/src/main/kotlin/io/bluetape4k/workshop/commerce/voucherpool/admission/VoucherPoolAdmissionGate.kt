@file:Suppress("MagicNumber")

package io.bluetape4k.workshop.commerce.voucherpool.admission

import io.bluetape4k.bucket4j.ratelimit.RateLimitResult
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.hashOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * `AdmissionNamespace`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 * @property defaultLimitPerMinute 처리량·할당량·크기 또는 진척도입니다. 음수와 상한을 검증하고 경쟁 요청에서 원자적으로 갱신합니다.
 */
internal enum class AdmissionNamespace(val defaultLimitPerMinute: Int) {
    /**
     * `AdmissionNamespace.RESERVE`은 `reserve` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤
     * 사용합니다.
     */
    RESERVE(20),
    /**
     * `AdmissionNamespace.ALLOCATE`은 `allocate` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한
     * 뒤 사용합니다.
     */
    ALLOCATE(20),
    /**
     * `AdmissionNamespace.REVEAL`은 `reveal` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤
     * 사용합니다.
     */
    REVEAL(5),
    /**
     * `AdmissionNamespace.REDEEM`은 권리 사용을 확정하는 command입니다.
     */
    REDEEM(10),
    /**
     * `AdmissionNamespace.OPERATOR_AUTH`은 `operator auth` 업무 상황을 나타냅니다. 현재 aggregate의 revision과
     * 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    OPERATOR_AUTH(5),
}

/**
 * `AdmissionDecision`은 정책 결정입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
internal enum class AdmissionDecision {
    /**
     * `AdmissionDecision.ALLOW`은 `allow` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    ALLOW,
    /**
     * `AdmissionDecision.RATE_LIMITED`은 허용된 요청 속도를 초과해 admission에서 차단한 값입니다.
     */
    RATE_LIMITED,
    /**
     * `AdmissionDecision.DEGRADED_ALLOW`은 보조 의존성이 제한되지만 권위 데이터만으로 일부 기능을 계속할 수 있는 값입니다.
     */
    DEGRADED_ALLOW,
    /**
     * `AdmissionDecision.DATABASE_BUSY`은 `database busy` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된
     * 전이를 확인한
     * 뒤 사용합니다.
     */
    DATABASE_BUSY,
}

/**
 * `AdmissionState`은 수명주기 상태입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 */
internal enum class AdmissionState {
    /**
     * `AdmissionState.HEALTHY`은 `healthy` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를 확인합니다.
     */
    HEALTHY,
    /**
     * `AdmissionState.DEGRADED`은 보조 의존성이 제한되지만 권위 데이터만으로 일부 기능을 계속할 수 있는 값입니다.
     */
    DEGRADED,
    /**
     * `AdmissionState.RECOVERING`은 `recovering` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를
     * 확인합니다.
     */
    RECOVERING,
}

/**
 * VoucherPoolAdmissionBackend은 사전 생성 바우처 풀 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
internal fun interface VoucherPoolAdmissionBackend {
    /**
     * consume은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param namespace 업무 처리에 필요한 namespace 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun consume(namespace: AdmissionNamespace, key: String): RateLimitResult
}

/**
 * AdmissionLimits은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class AdmissionLimits private constructor(
    values: Map<AdmissionNamespace, Int>,
) {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val values = values.toMap()

    init {
        require(this.values.keys == AdmissionNamespace.entries.toSet()) {
            "all admission namespaces must be configured"
        }
        require(this.values.values.all { it > 0 }) { "admission limits must be positive" }
    }

    /**
     * get은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param namespace 업무 처리에 필요한 namespace 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    operator fun get(namespace: AdmissionNamespace): Int = values.getValue(namespace)

    /**
     * withLimit은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param namespace 업무 처리에 필요한 namespace 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    fun withLimit(namespace: AdmissionNamespace, limit: Int): AdmissionLimits =
        AdmissionLimits(values + (namespace to limit))

    companion object {
        /**
         * defaults은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        fun defaults(): AdmissionLimits =
            AdmissionLimits(AdmissionNamespace.entries.associateWith { it.defaultLimitPerMinute })
    }
}
/**
 * AdmissionRecoveryPolicy은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property failureThreshold 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property recoverySuccessThreshold 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property probeInterval 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property maxInFlightProbes 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class AdmissionRecoveryPolicy(
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
) {
    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
        require(recoverySuccessThreshold > 0) { "recoverySuccessThreshold must be positive" }
        require(!probeInterval.isNegative && !probeInterval.isZero) { "probeInterval must be positive" }
        require(maxInFlightProbes > 0) { "maxInFlightProbes must be positive" }
    }
}

/**
 * Redis는 advisory load shedding에만 사용하고 bounded node-local policy로 fallback합니다.
 *
 * PostgreSQL command는 여전히 [DatabasePermitGate]를 통과하므로,
 * Redis availability가 voucher의 terminal outcome을 결정하지 않습니다.
 * @property backend 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property recoveryPolicy 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property keyFactory 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property stateObserver 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */
@Suppress("TooManyFunctions")
internal class VoucherPoolAdmissionGate(
    /**
     * `backend`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val backend: VoucherPoolAdmissionBackend?,
    limits: AdmissionLimits = AdmissionLimits.defaults(),
    /**
     * `recoveryPolicy`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val recoveryPolicy: AdmissionRecoveryPolicy = AdmissionRecoveryPolicy(),
    /**
     * `clock`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock = Clock.systemUTC(),
    /**
     * `keyFactory`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    private val keyFactory: VoucherPoolRedisSignalKeyFactory = VoucherPoolRedisSignalKeyFactory(),
    /**
     * `stateObserver`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private val stateObserver: (AdmissionState) -> Unit = {},
) {

    companion object: KLogging()

    /**
     * `localLimiter`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val localLimiter = NodeLocalAdmissionLimiter(limits, clock)
    /**
     * `stateLock`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private val stateLock = ReentrantLock()
    /**
     * `currentState`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private var currentState = if (backend == null) AdmissionState.DEGRADED else AdmissionState.HEALTHY
    /**
     * `consecutiveFailures`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private var consecutiveFailures = 0
    /**
     * `recoverySuccesses`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private var recoverySuccesses = 0
    /**
     * `nextProbeAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private var nextProbeAt = Instant.MIN
    /**
     * `probesInFlight`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private var probesInFlight = 0

    init {
        stateObserver(currentState)
    }

    /**
     * admit은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param namespace 업무 처리에 필요한 namespace 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param principalDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun admit(namespace: AdmissionNamespace, principalDigest: ByteArray): AdmissionDecision {
        val redis = backend ?: return localDecision(namespace, principalDigest)
        val now = Instant.now(clock)
        val backendCall = reserveBackendCall(now) ?: return localDecision(namespace, principalDigest)
        val key = keyFactory.admissionKey(namespace, principalDigest)

        return try {
            val result = redis.consume(namespace, key)
            when {
                result.isConsumed -> distributedSuccess(backendCall.probe, namespace, principalDigest)
                result.isRejected -> {
                    recordSuccess()
                    if (state() == AdmissionState.HEALTHY) AdmissionDecision.RATE_LIMITED
                    else localDecision(namespace, principalDigest)
                }
                else -> {
                    recordFailure(now)
                    localDecision(namespace, principalDigest)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            recordFailure(now, failure)
            localDecision(namespace, principalDigest)
        } finally {
            if (backendCall.probe) releaseProbe()
        }
    }

    /**
     * state은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun state(): AdmissionState = stateLock.withLock { currentState }

    /**
     * distributedSuccess은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param probe 업무 처리에 필요한 probe 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param namespace 업무 처리에 필요한 namespace 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param principalDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    private fun distributedSuccess(
        probe: Boolean,
        namespace: AdmissionNamespace,
        principalDigest: ByteArray,
    ): AdmissionDecision {
        val previous = state()
        recordSuccess()
        return if (probe || previous != AdmissionState.HEALTHY) {
            localDecision(namespace, principalDigest)
        } else {
            AdmissionDecision.ALLOW
        }
    }

    /**
     * localDecision은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param namespace 업무 처리에 필요한 namespace 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param principalDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    private fun localDecision(namespace: AdmissionNamespace, principalDigest: ByteArray): AdmissionDecision =
        if (localLimiter.tryAcquire(namespace, keyFactory.admissionKey(namespace, principalDigest))) {
            AdmissionDecision.DEGRADED_ALLOW
        } else {
            AdmissionDecision.RATE_LIMITED
        }

    /**
     * reserveBackendCall은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun reserveBackendCall(now: Instant): BackendCall? = stateLock.withLock {
        when (currentState) {
            AdmissionState.HEALTHY    -> BackendCall(probe = false)
            AdmissionState.DEGRADED   -> {
                if (now.isBefore(nextProbeAt) || probesInFlight >= recoveryPolicy.maxInFlightProbes) {
                    null
                } else {
                    probesInFlight++
                    BackendCall(probe = true)
                }
            }
            AdmissionState.RECOVERING -> {
                if (probesInFlight >= recoveryPolicy.maxInFlightProbes) {
                    null
                } else {
                    probesInFlight++
                    BackendCall(probe = true)
                }
            }
        }
    }

    /**
     * recordSuccess은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun recordSuccess() {
        stateLock.withLock {
            when (currentState) {
                AdmissionState.HEALTHY  -> consecutiveFailures = 0
                AdmissionState.DEGRADED -> transitionToRecovering()
                AdmissionState.RECOVERING -> {
                    recoverySuccesses++
                    completeRecoveryIfReady()
                }
            }
        }
    }

    /**
     * transitionToRecovering은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게
     * 재처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun transitionToRecovering() {
        currentState = AdmissionState.RECOVERING
        recoverySuccesses = 1
        stateObserver(currentState)
        log.debug { "voucher_pool_admission_state from=DEGRADED to=RECOVERING" }
        completeRecoveryIfReady()
    }

    /**
     * completeRecoveryIfReady은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun completeRecoveryIfReady() {
        if (recoverySuccesses >= recoveryPolicy.recoverySuccessThreshold) {
            currentState = AdmissionState.HEALTHY
            consecutiveFailures = 0
            recoverySuccesses = 0
            stateObserver(currentState)
            log.debug { "voucher_pool_admission_state from=RECOVERING to=HEALTHY" }
        }
    }

    /**
     * recordFailure은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun recordFailure(now: Instant, failure: Exception? = null) {
        stateLock.withLock {
            when (currentState) {
                AdmissionState.HEALTHY  -> {
                    consecutiveFailures++
                    if (consecutiveFailures >= recoveryPolicy.failureThreshold) degrade(now)
                }
                AdmissionState.DEGRADED -> nextProbeAt = now.plus(recoveryPolicy.probeInterval)
                AdmissionState.RECOVERING -> degrade(now)
            }
        }
        log.warn {
            "voucher_pool_admission_redis_failed fallback=NODE_LOCAL failure=" +
                    (failure?.javaClass?.simpleName ?: "RESULT_ERROR")
        }
    }

    /**
     * degrade은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun degrade(now: Instant) {
        val previous = currentState
        currentState = AdmissionState.DEGRADED
        consecutiveFailures = 0
        recoverySuccesses = 0
        nextProbeAt = now.plus(recoveryPolicy.probeInterval)
        stateObserver(currentState)
        log.debug { "voucher_pool_admission_state from=$previous to=DEGRADED" }
    }

    /**
     * releaseProbe은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun releaseProbe() {
        stateLock.withLock {
            check(probesInFlight > 0) { "admission probe accounting underflow" }
            probesInFlight--
        }
    }
    /**
     * BackendCall은 프로모션 바우처에서 사용하는 업무 객체입니다.
     * @property probe 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
     */


    private class BackendCall(val probe: Boolean)

}
/**
 * NodeLocalAdmissionLimiter은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property limits 처리량·할당량·크기 또는 진척도입니다. 음수와 상한을 검증하고 경쟁 요청에서 원자적으로 갱신합니다.
 * @property clock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


private class NodeLocalAdmissionLimiter(
    /**
     * `limits`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val limits: AdmissionLimits,
    /**
     * `clock`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
) {

    private companion object {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val SECONDS_PER_MINUTE = 60L
        /**
         * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
         */
        const val MAX_LOCAL_KEYS = 10_000
    }
    /**
     * `counters`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */

    private val counters = ConcurrentHashMap<LocalKey, WindowCounter>()
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    private val keyLock = ReentrantLock()

    /**
     * tryAcquire은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param namespace 업무 처리에 필요한 namespace 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun tryAcquire(namespace: AdmissionNamespace, key: String): Boolean {
        val minute = Instant.now(clock).epochSecond / SECONDS_PER_MINUTE
        val localKey = LocalKey(namespace, key)
        val counter = counters[localKey] ?: register(localKey, minute) ?: return false
        return counter.tryAcquire(minute, limits[namespace])
    }

    /**
     * register은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param localKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param minute 업무 처리에 필요한 minute 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun register(localKey: LocalKey, minute: Long): WindowCounter? = keyLock.withLock {
        counters[localKey]
            ?: run {
                if (counters.size >= MAX_LOCAL_KEYS) {
                    counters.entries.removeIf { !it.value.belongsTo(minute) }
                }
                if (counters.size >= MAX_LOCAL_KEYS) null
                else WindowCounter(minute).also { counters[localKey] = it }
            }
    }

    /**
     * WindowCounter은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    private class WindowCounter(initialMinute: Long) {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val lock = ReentrantLock()
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */

        @Volatile
        private var minute = initialMinute
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private var consumed = 0

        /**
         * tryAcquire은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param currentMinute 업무 처리에 필요한 currentMinute 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
         */
        fun tryAcquire(currentMinute: Long, limit: Int): Boolean = lock.withLock {
            if (minute != currentMinute) {
                minute = currentMinute
                consumed = 0
            }
            if (consumed >= limit) return false
            consumed++
            true
        }

        /**
         * belongsTo은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param currentMinute 업무 처리에 필요한 currentMinute 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        fun belongsTo(currentMinute: Long): Boolean = minute == currentMinute
    }
    /**
     * LocalKey은 프로모션 바우처에서 사용하는 업무 객체입니다.
     * @property namespace 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
     * @property key 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */


    private class LocalKey(
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val namespace: AdmissionNamespace,
        /**
         * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
         */
        val key: String,
    ) {
        /**
         * equals은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param other 업무 처리에 필요한 other 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        override fun equals(other: Any?): Boolean =
            other is LocalKey && namespace == other.namespace && key == other.key

        /**
         * hashCode은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        override fun hashCode(): Int = hashOf(namespace, key)

        /**
         * toString은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        override fun toString(): String = "LocalKey(namespace=$namespace,key=$key)"
    }

}
