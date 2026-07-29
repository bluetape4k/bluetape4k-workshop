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
 * optional distributed advisory permit 전에 항상 켜져 있는 local JDBC bulkhead를 적용합니다.
 * Redis가 없거나 사용할 수 없을 때도 PostgreSQL이 correctness authority로 남습니다.
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
                // Redis는 advisory입니다. release failure가 authoritative DB outcome을 대체하면 안 됩니다.
                log.warn { "reservation_admission_release_failed reason=REDIS_UNAVAILABLE" }
            }
        }
    }
}

/**
 * published `bluetape4k-lettuce:1.11.0` semaphore API용 adapter입니다.
 *
 * version 1.11.0에는 expiring permit/owner lease가 없습니다. 따라서 caller는 `finally`에서 release해야 하고,
 * 이 semaphore를 reservation capacity authority로 사용하면 안 되며, Redis reset을 견뎌야 합니다.
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
        // SET NX는 idempotent이며 Redis flush/restart 뒤 advisory counter도 복원합니다.
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
