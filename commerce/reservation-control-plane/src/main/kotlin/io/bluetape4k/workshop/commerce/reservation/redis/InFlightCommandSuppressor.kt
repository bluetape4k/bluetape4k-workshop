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
    /** Returns a lease when acquired, or `null` when another command currently owns it. */
    fun tryAcquire(opaqueCommandId: String): CommandSuppressionLease?
}

/**
 * Suppresses duplicate in-flight work when Redis is healthy and delegates correctness to the
 * PostgreSQL idempotency record whenever Redis is unavailable.
 */
class InFlightCommandSuppressor(
    private val backend: CommandSuppressionBackend? = null,
) {
    companion object : KLogging()

    fun <T> execute(opaqueCommandId: String, action: () -> T): SuppressionOutcome<T> {
        val currentBackend = backend
            ?: return SuppressionOutcome.Executed(action(), SuppressionMode.POSTGRES_FALLBACK)
        val lease = try {
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
 * Token-checked two-second suppression leases backed by `bluetape4k-lettuce:1.11.0`.
 * [opaqueCommandId] must be a bounded HMAC-derived identifier, never a raw idempotency key or owner token.
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
        val lock = LettuceLock(
            connection = connection,
            lockKey = "$keyPrefix:$opaqueCommandId",
            defaultLeaseTime = leaseTime,
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
