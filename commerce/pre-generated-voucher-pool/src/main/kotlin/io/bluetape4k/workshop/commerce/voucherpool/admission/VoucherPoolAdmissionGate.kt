@file:Suppress("MagicNumber")

package io.bluetape4k.workshop.commerce.voucherpool.admission

import io.bluetape4k.bucket4j.ratelimit.RateLimitResult
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class AdmissionNamespace(val defaultLimitPerMinute: Int) {
    RESERVE(20),
    ALLOCATE(20),
    REVEAL(5),
    REDEEM(10),
    OPERATOR_AUTH(5),
}

internal enum class AdmissionDecision {
    ALLOW,
    RATE_LIMITED,
    DEGRADED_ALLOW,
    DATABASE_BUSY,
}

internal enum class AdmissionState {
    HEALTHY,
    DEGRADED,
    RECOVERING,
}

internal fun interface VoucherPoolAdmissionBackend {
    fun consume(namespace: AdmissionNamespace, key: String): RateLimitResult
}

internal class AdmissionLimits private constructor(
    values: Map<AdmissionNamespace, Int>,
) {
    private val values = values.toMap()

    init {
        require(this.values.keys == AdmissionNamespace.entries.toSet()) {
            "all admission namespaces must be configured"
        }
        require(this.values.values.all { it > 0 }) { "admission limits must be positive" }
    }

    operator fun get(namespace: AdmissionNamespace): Int = values.getValue(namespace)

    fun withLimit(namespace: AdmissionNamespace, limit: Int): AdmissionLimits =
        AdmissionLimits(values + (namespace to limit))

    companion object {
        fun defaults(): AdmissionLimits =
            AdmissionLimits(AdmissionNamespace.entries.associateWith { it.defaultLimitPerMinute })
    }
}

internal class AdmissionRecoveryPolicy(
    val failureThreshold: Int = 1,
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
 * Redis는 advisory load shedding에만 사용하고 bounded node-local policy로 fallback합니다.
 *
 * PostgreSQL command는 여전히 [DatabasePermitGate]를 통과하므로,
 * Redis availability가 voucher의 terminal outcome을 결정하지 않습니다.
 */
@Suppress("TooManyFunctions")
internal class VoucherPoolAdmissionGate(
    private val backend: VoucherPoolAdmissionBackend?,
    limits: AdmissionLimits = AdmissionLimits.defaults(),
    private val recoveryPolicy: AdmissionRecoveryPolicy = AdmissionRecoveryPolicy(),
    private val clock: Clock = Clock.systemUTC(),
    private val keyFactory: VoucherPoolRedisSignalKeyFactory = VoucherPoolRedisSignalKeyFactory(),
    private val stateObserver: (AdmissionState) -> Unit = {},
) {
    private val localLimiter = NodeLocalAdmissionLimiter(limits, clock)
    private val stateLock = ReentrantLock()
    private var currentState = if (backend == null) AdmissionState.DEGRADED else AdmissionState.HEALTHY
    private var consecutiveFailures = 0
    private var recoverySuccesses = 0
    private var nextProbeAt = Instant.MIN
    private var probesInFlight = 0

    init {
        stateObserver(currentState)
    }

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

    fun state(): AdmissionState = stateLock.withLock { currentState }

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

    private fun localDecision(namespace: AdmissionNamespace, principalDigest: ByteArray): AdmissionDecision =
        if (localLimiter.tryAcquire(namespace, keyFactory.admissionKey(namespace, principalDigest))) {
            AdmissionDecision.DEGRADED_ALLOW
        } else {
            AdmissionDecision.RATE_LIMITED
        }

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
                AdmissionState.DEGRADED -> transitionToRecovering()
                AdmissionState.RECOVERING -> {
                    recoverySuccesses++
                    completeRecoveryIfReady()
                }
            }
        }
    }

    private fun transitionToRecovering() {
        currentState = AdmissionState.RECOVERING
        recoverySuccesses = 1
        stateObserver(currentState)
        log.debug { "voucher_pool_admission_state from=DEGRADED to=RECOVERING" }
        completeRecoveryIfReady()
    }

    private fun completeRecoveryIfReady() {
        if (recoverySuccesses >= recoveryPolicy.recoverySuccessThreshold) {
            currentState = AdmissionState.HEALTHY
            consecutiveFailures = 0
            recoverySuccesses = 0
            stateObserver(currentState)
            log.debug { "voucher_pool_admission_state from=RECOVERING to=HEALTHY" }
        }
    }

    private fun recordFailure(now: Instant, failure: Exception? = null) {
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
            "voucher_pool_admission_redis_failed fallback=NODE_LOCAL failure=" +
                (failure?.javaClass?.simpleName ?: "RESULT_ERROR")
        }
    }

    private fun degrade(now: Instant) {
        val previous = currentState
        currentState = AdmissionState.DEGRADED
        consecutiveFailures = 0
        recoverySuccesses = 0
        nextProbeAt = now.plus(recoveryPolicy.probeInterval)
        stateObserver(currentState)
        log.debug { "voucher_pool_admission_state from=$previous to=DEGRADED" }
    }

    private fun releaseProbe() {
        stateLock.withLock {
            check(probesInFlight > 0) { "admission probe accounting underflow" }
            probesInFlight--
        }
    }

    private class BackendCall(val probe: Boolean)

    companion object : KLogging()
}

private class NodeLocalAdmissionLimiter(
    private val limits: AdmissionLimits,
    private val clock: Clock,
) {
    private val counters = ConcurrentHashMap<LocalKey, WindowCounter>()
    private val keyLock = ReentrantLock()

    fun tryAcquire(namespace: AdmissionNamespace, key: String): Boolean {
        val minute = Instant.now(clock).epochSecond / SECONDS_PER_MINUTE
        val localKey = LocalKey(namespace, key)
        val counter = counters[localKey] ?: register(localKey, minute) ?: return false
        return counter.tryAcquire(minute, limits[namespace])
    }

    private fun register(localKey: LocalKey, minute: Long): WindowCounter? =
        keyLock.withLock {
            counters[localKey] ?: run {
                if (counters.size >= MAX_LOCAL_KEYS) {
                    counters.entries.removeIf { !it.value.belongsTo(minute) }
                }
                if (counters.size >= MAX_LOCAL_KEYS) null
                else WindowCounter(minute).also { counters[localKey] = it }
            }
        }

    private class WindowCounter(initialMinute: Long) {
        private val lock = ReentrantLock()
        @Volatile
        private var minute = initialMinute
        private var consumed = 0

        fun tryAcquire(currentMinute: Long, limit: Int): Boolean =
            lock.withLock {
                if (minute != currentMinute) {
                    minute = currentMinute
                    consumed = 0
                }
                if (consumed >= limit) return false
                consumed++
                true
            }

        fun belongsTo(currentMinute: Long): Boolean = minute == currentMinute
    }

    private class LocalKey(val namespace: AdmissionNamespace, val key: String) {
        override fun equals(other: Any?): Boolean =
            other is LocalKey && namespace == other.namespace && key == other.key

        override fun hashCode(): Int = 31 * namespace.hashCode() + key.hashCode()
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
        const val MAX_LOCAL_KEYS = 10_000
    }
}
