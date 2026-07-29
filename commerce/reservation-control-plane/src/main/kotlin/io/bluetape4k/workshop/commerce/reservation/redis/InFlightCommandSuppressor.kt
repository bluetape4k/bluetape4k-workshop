package io.bluetape4k.workshop.commerce.reservation.redis

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.redis.lettuce.lock.LettuceLock
import io.lettuce.core.api.StatefulRedisConnection
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.CancellationException

enum class SuppressionMode { REDIS_ADVISORY, POSTGRES_FALLBACK }

sealed interface SuppressionOutcome<out T> : Serializable {
    data class Executed<out T>(
        val value: T,
        val mode: SuppressionMode,
    ) : SuppressionOutcome<T> {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data object Suppressed : SuppressionOutcome<Nothing>
}

fun interface CommandSuppressionLease : AutoCloseable {
    override fun close()
}

interface CommandSuppressionBackend {
    /** lease를 획득하면 반환하고, 다른 command가 현재 소유 중이면 `null`을 반환합니다. */
    fun tryAcquire(opaqueCommandId: String): CommandSuppressionLease?
}

/**
 * Redis가 정상일 때 duplicate in-flight work를 억제하고,
 * Redis를 사용할 수 없으면 correctness를 PostgreSQL idempotency record에 위임합니다.
 */
class InFlightCommandSuppressor(
    private val backend: CommandSuppressionBackend? = null,
) {
    companion object : KLogging()

    fun <T> execute(
        opaqueCommandId: String,
        action: () -> T,
    ): SuppressionOutcome<T> {
        val currentBackend =
            backend
                ?: return SuppressionOutcome.Executed(action(), SuppressionMode.POSTGRES_FALLBACK)
        val lease =
            try {
                currentBackend.tryAcquire(opaqueCommandId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (_: Exception) {
                log.warn { "reservation_suppression_degraded reason=REDIS_UNAVAILABLE fallback=POSTGRES" }
                return SuppressionOutcome.Executed(action(), SuppressionMode.POSTGRES_FALLBACK)
            }

        if (lease == null) {
            log.debug { "reservation_suppression_hit outcome=SUPPRESSED" }
            return SuppressionOutcome.Suppressed
        }

        return try {
            SuppressionOutcome.Executed(action(), SuppressionMode.REDIS_ADVISORY)
        } finally {
            try {
                lease.close()
                log.debug { "reservation_suppression_released mode=REDIS_ADVISORY" }
            } catch (_: Exception) {
                log.warn { "reservation_suppression_release_failed reason=REDIS_UNAVAILABLE" }
            }
        }
    }
}

/**
 * `bluetape4k-lettuce:1.11.0`으로 구현한 token-checked 2초 suppression lease입니다.
 * [opaqueCommandId]는 bounded HMAC-derived identifier여야 하며, raw idempotency key나 owner token이면 안 됩니다.
 */
class LettuceLockSuppressionBackend(
    private val connection: StatefulRedisConnection<String, String>,
    private val keyPrefix: String = "reservation:suppression",
    private val leaseTime: Duration = Duration.ofSeconds(2),
) : CommandSuppressionBackend {
    companion object : KLogging() {
        private val OPAQUE_ID = Regex("[A-Za-z0-9_-]{8,32}")
    }

    init {
        require(!leaseTime.isNegative && !leaseTime.isZero) { "leaseTime must be positive" }
        require(keyPrefix.isNotBlank()) { "keyPrefix must not be blank" }
    }

    override fun tryAcquire(opaqueCommandId: String): CommandSuppressionLease? {
        require(OPAQUE_ID.matches(opaqueCommandId)) {
            "opaqueCommandId must be an 8 to 32 character HMAC-derived identifier"
        }
        val lock =
            LettuceLock(
                connection = connection,
                lockKey = "$keyPrefix:$opaqueCommandId",
                defaultLeaseTime = leaseTime
            )
        if (!lock.tryLock(waitTime = Duration.ZERO, leaseTime = leaseTime)) {
            return null
        }
        log.debug { "reservation_suppression_acquired mode=REDIS_ADVISORY" }
        return CommandSuppressionLease {
            if (lock.isHeldByCurrentInstance()) {
                lock.unlock()
            }
        }
    }
}
