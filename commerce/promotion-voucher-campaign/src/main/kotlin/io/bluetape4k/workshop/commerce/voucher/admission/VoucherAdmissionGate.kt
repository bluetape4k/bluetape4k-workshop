package io.bluetape4k.workshop.commerce.voucher.admission

import io.bluetape4k.bucket4j.ratelimit.RateLimiter
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
 * AdmissionDecision은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal sealed interface AdmissionDecision {
    /**
     * Proceed은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object Proceed: AdmissionDecision
/**
 * RateLimited은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property retryAfter 같은 업무를 재시도한 횟수 또는 다음 재시도까지의 대기 정책입니다. 최대 시도 횟수와 terminal failure 규칙을 함께
 * 적용합니다.
 */


    data class RateLimited(
        /**
         * `retryAfter`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val retryAfter: Duration,
    ): AdmissionDecision
/**
 * DatabaseBusy은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property retryAfter 같은 업무를 재시도한 횟수 또는 다음 재시도까지의 대기 정책입니다. 최대 시도 횟수와 terminal failure 규칙을 함께
 * 적용합니다.
 */


    data class DatabaseBusy(
        /**
         * `retryAfter`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val retryAfter: Duration,
    ): AdmissionDecision
}
/**
 * AdmissionRecoveryPolicy은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property failureThreshold 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property recoverySuccessThreshold 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야
 * 합니다.
 * @property probeInterval 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property maxInFlightProbes 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class AdmissionRecoveryPolicy(
    /**
     * `failureThreshold`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val failureThreshold: Int = 3,
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
 * Redis는 load shedding 용도로만 사용합니다. backend failure는 항상 PostgreSQL-authoritative work로 degrade됩니다.
 *
 * recovery probe는 기본적으로 request-driven single-flight 방식이므로,
 * Redis를 사용할 수 없는 동안 scheduler나 JDBC permit을 보유하지 않습니다.
 * @property rateLimiter 처리량·할당량·크기 또는 진척도입니다. 음수와 상한을 검증하고 경쟁 요청에서 원자적으로 갱신합니다.
 * @property recoveryPolicy 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal class VoucherAdmissionGate(
    /**
     * `rateLimiter`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val rateLimiter: RateLimiter<String>?,
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
) {

    companion object: KLogging() {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val DEFAULT_RETRY_AFTER = 1.seconds()
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val MINIMUM_RETRY_AFTER = 1.millis()
    }
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */

    private val stateLock = ReentrantLock()
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    private var currentState = if (rateLimiter == null) AdmissionState.DEGRADED else AdmissionState.HEALTHY
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private var consecutiveFailures = 0
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private var recoverySuccesses = 0
    /**
     * 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    private var nextProbeAt = Instant.MIN
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private var probesInFlight = 0

    /**
     * decide은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param rateKey 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun decide(rateKey: String): AdmissionDecision {
        rateKey.requireNotBlank("rateKey")

        val limiter = rateLimiter ?: return AdmissionDecision.Proceed
        val now = Instant.now(clock)
        val backendCall = reserveBackendCall(now) ?: return AdmissionDecision.Proceed

        return try {
            val result = limiter.consume(rateKey, 1)
            when {
                result.isConsumed -> {
                    recordSuccess()
                    AdmissionDecision.Proceed
                }
                result.isRejected -> {
                    recordSuccess()
                    AdmissionDecision.RateLimited(
                        (result.retryAfter ?: DEFAULT_RETRY_AFTER).coerceAtLeast(MINIMUM_RETRY_AFTER)
                    )
                }
                else              -> {
                    recordFailure(now, backendCall.probe)
                    AdmissionDecision.Proceed
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recordFailure(now, backendCall.probe, failure)
            AdmissionDecision.Proceed
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
     * reserveBackendCall은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun reserveBackendCall(now: Instant): BackendCall? =
        stateLock.withLock {
            when (currentState) {
                AdmissionState.HEALTHY  -> BackendCall(probe = false)
                AdmissionState.DEGRADED -> {
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

                AdmissionState.DEGRADED -> {
                    currentState = AdmissionState.RECOVERING
                    recoverySuccesses = 1
                    log.debug { "voucher_admission_state from=DEGRADED to=RECOVERING" }
                    completeRecoveryIfReady()
                }

                AdmissionState.RECOVERING -> {
                    recoverySuccesses++
                    completeRecoveryIfReady()
                }
            }
        }
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
            log.debug { "voucher_admission_state from=RECOVERING to=HEALTHY" }
        }
    }

    /**
     * recordFailure은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param probe 업무 처리에 필요한 probe 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun recordFailure(
        now: Instant,
        probe: Boolean,
        failure: Exception? = null,
    ) {
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
            "voucher_admission_redis_failed probe=$probe fallback=POSTGRES failure=${failure?.javaClass?.simpleName ?: "RESULT_ERROR"}"
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
        nextProbeAt = now + recoveryPolicy.probeInterval
        log.debug { "voucher_admission_state from=$previous to=DEGRADED" }
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
 * BackendCall은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property probe 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


    private data class BackendCall(val probe: Boolean)
}
