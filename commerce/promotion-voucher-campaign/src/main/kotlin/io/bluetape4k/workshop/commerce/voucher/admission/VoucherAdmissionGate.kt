package io.bluetape4k.workshop.commerce.voucher.admission

import io.bluetape4k.bucket4j.ratelimit.RateLimiter
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class AdmissionState {
    HEALTHY,
    DEGRADED,
    RECOVERING,
}

internal sealed interface AdmissionDecision {
    data object Proceed : AdmissionDecision

    data class RateLimited(
        val retryAfter: Duration,
    ) : AdmissionDecision

    data class DatabaseBusy(
        val retryAfter: Duration,
    ) : AdmissionDecision
}

internal data class AdmissionRecoveryPolicy(
    val failureThreshold: Int = 3,
    val recoverySuccessThreshold: Int = 3,
    val probeInterval: Duration = Duration.ofSeconds(1),
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
 */
internal class VoucherAdmissionGate(
    private val rateLimiter: RateLimiter<String>?,
    private val recoveryPolicy: AdmissionRecoveryPolicy = AdmissionRecoveryPolicy(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val stateLock = ReentrantLock()
    private var currentState = if (rateLimiter == null) AdmissionState.DEGRADED else AdmissionState.HEALTHY
    private var consecutiveFailures = 0
    private var recoverySuccesses = 0
    private var nextProbeAt = Instant.MIN
    private var probesInFlight = 0

    fun decide(rateKey: String): AdmissionDecision {
        require(rateKey.isNotBlank()) { "rateKey must not be blank" }
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
                        (result.retryAfter ?: DEFAULT_RETRY_AFTER).coerceAtLeast(MINIMUM_RETRY_AFTER),
                    )
                }

                else -> {
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

    fun state(): AdmissionState = stateLock.withLock { currentState }

    private fun reserveBackendCall(now: Instant): BackendCall? =
        stateLock.withLock {
            when (currentState) {
                AdmissionState.HEALTHY -> BackendCall(probe = false)
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

    private fun recordSuccess() {
        stateLock.withLock {
            when (currentState) {
                AdmissionState.HEALTHY -> consecutiveFailures = 0
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

    private fun completeRecoveryIfReady() {
        if (recoverySuccesses >= recoveryPolicy.recoverySuccessThreshold) {
            currentState = AdmissionState.HEALTHY
            consecutiveFailures = 0
            recoverySuccesses = 0
            log.debug { "voucher_admission_state from=RECOVERING to=HEALTHY" }
        }
    }

    private fun recordFailure(
        now: Instant,
        probe: Boolean,
        failure: Exception? = null,
    ) {
        stateLock.withLock {
            when (currentState) {
                AdmissionState.HEALTHY -> {
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

    private fun degrade(now: Instant) {
        val previous = currentState
        currentState = AdmissionState.DEGRADED
        consecutiveFailures = 0
        recoverySuccesses = 0
        nextProbeAt = now.plus(recoveryPolicy.probeInterval)
        log.debug { "voucher_admission_state from=$previous to=DEGRADED" }
    }

    private fun releaseProbe() {
        stateLock.withLock {
            check(probesInFlight > 0) { "admission probe accounting underflow" }
            probesInFlight--
        }
    }

    private data class BackendCall(val probe: Boolean)

    companion object : KLogging() {
        private val DEFAULT_RETRY_AFTER = Duration.ofSeconds(1)
        private val MINIMUM_RETRY_AFTER = Duration.ofMillis(1)
    }
}
