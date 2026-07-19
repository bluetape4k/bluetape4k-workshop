package io.bluetape4k.workshop.commerce.reservation.redis

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.redis.lettuce.semaphore.LettuceSemaphore
import io.bluetape4k.support.requirePositiveNumber
import io.lettuce.core.api.StatefulRedisConnection
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.LockSupport

enum class AdmissionMode { REDIS_ADVISORY, LOCAL_FALLBACK }

enum class AdmissionRejection { LOCAL_CAPACITY, REDIS_CAPACITY }

sealed interface AdmissionOutcome<out T> : Serializable {
    data class Executed<out T>(
        val value: T,
        val mode: AdmissionMode,
    ) : AdmissionOutcome<T> {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class Rejected(
        val reason: AdmissionRejection,
    ) : AdmissionOutcome<Nothing> {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}

interface AdmissionPermitBackend {
    fun tryAcquire(waitTime: Duration): Boolean

    fun release()
}

/**
 * Applies the always-on local JDBC bulkhead before the optional distributed advisory permit.
 * PostgreSQL remains the correctness authority when Redis is absent or unavailable.
 */
class ReservationAdmissionGate(
    private val localBulkhead: NodeLocalDatabaseBulkhead,
    private val redisBackend: AdmissionPermitBackend? = null,
    private val redisWaitTime: Duration = Duration.ofMillis(100),
) {
    companion object : KLogging()

    init {
        require(!redisWaitTime.isNegative) { "redisWaitTime must not be negative" }
    }

    fun <T> execute(action: () -> T): AdmissionOutcome<T> =
        when (val local = localBulkhead.execute(DatabaseWorkload.FOREGROUND) { executeAfterLocalAcquire(action) }) {
            is DatabaseBulkheadOutcome.Executed -> local.value
            is DatabaseBulkheadOutcome.Rejected -> AdmissionOutcome.Rejected(AdmissionRejection.LOCAL_CAPACITY)
        }

    private fun <T> executeAfterLocalAcquire(action: () -> T): AdmissionOutcome<T> {
        val backend =
            redisBackend
                ?: return AdmissionOutcome.Executed(action(), AdmissionMode.LOCAL_FALLBACK)

        val redisAcquired =
            try {
                backend.tryAcquire(redisWaitTime)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (_: Exception) {
                log.warn { "reservation_admission_degraded reason=REDIS_UNAVAILABLE fallback=LOCAL" }
                return AdmissionOutcome.Executed(action(), AdmissionMode.LOCAL_FALLBACK)
            }

        if (!redisAcquired) {
            log.debug { "reservation_admission_rejected reason=REDIS_CAPACITY" }
            return AdmissionOutcome.Rejected(AdmissionRejection.REDIS_CAPACITY)
        }

        return try {
            AdmissionOutcome.Executed(action(), AdmissionMode.REDIS_ADVISORY)
        } finally {
            try {
                backend.release()
                log.debug { "reservation_admission_released mode=REDIS_ADVISORY" }
            } catch (_: Exception) {
                // Redis is advisory. A release failure must not replace the authoritative DB outcome.
                log.warn { "reservation_admission_release_failed reason=REDIS_UNAVAILABLE" }
            }
        }
    }
}

/**
 * Adapter for the published `bluetape4k-lettuce:1.11.0` semaphore API.
 *
 * Version 1.11.0 has no expiring permit/owner lease. Therefore callers must release in `finally`,
 * must never use this semaphore as reservation capacity authority, and must tolerate Redis reset.
 */
class LettuceSemaphoreAdmissionBackend(
    connection: StatefulRedisConnection<String, String>,
    semaphoreKey: String = "reservation:admission",
    totalPermits: Int = 64,
    private val retryInterval: Duration = Duration.ofMillis(10),
) : AdmissionPermitBackend {
    companion object : KLogging()

    private val semaphore =
        LettuceSemaphore(
            connection = connection,
            semaphoreKey = semaphoreKey,
            totalPermits = totalPermits.requirePositiveNumber("totalPermits")
        )

    init {
        require(!retryInterval.isNegative && !retryInterval.isZero) { "retryInterval must be positive" }
    }

    override fun tryAcquire(waitTime: Duration): Boolean {
        require(!waitTime.isNegative) { "waitTime must not be negative" }
        // SET NX is idempotent and also restores the advisory counter after Redis flush/restart.
        semaphore.initialize()
        val deadline = System.nanoTime() + waitTime.toNanos()
        do {
            if (semaphore.tryAcquire()) {
                log.debug { "reservation_admission_acquired mode=REDIS_ADVISORY" }
                return true
            }
            if (System.nanoTime() < deadline) {
                LockSupport.parkNanos(retryInterval.toNanos())
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("interrupted while waiting for advisory admission")
                }
            }
        } while (System.nanoTime() < deadline)
        return false
    }

    override fun release() {
        semaphore.release()
    }
}
