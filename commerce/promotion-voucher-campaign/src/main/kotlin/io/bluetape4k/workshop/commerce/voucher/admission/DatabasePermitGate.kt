package io.bluetape4k.workshop.commerce.voucher.admission

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

internal enum class DatabaseLane {
    FOREGROUND,
    WORKER,
    SSE_MAINTENANCE,
}

internal class DatabasePermitRejected(
    val retryAfter: Duration,
    cause: Throwable? = null,
) : RuntimeException("database permit unavailable", cause)

/**
 * Keeps virtual-thread concurrency outside the bounded JDBC connection pool.
 *
 * Each lane owns a fair semaphore so interactive traffic, the reconciliation worker, and SSE
 * maintenance cannot consume one another's reserved database capacity.
 */
internal class DatabasePermitGate(
    foregroundPermits: Int = 12,
    workerPermits: Int = 1,
    sseMaintenancePermits: Int = 3,
    private val acquireTimeout: Duration = Duration.ofMillis(250),
) {
    private val semaphores =
        mapOf(
            DatabaseLane.FOREGROUND to Semaphore(foregroundPermits.requirePositiveNumber("foregroundPermits"), true),
            DatabaseLane.WORKER to Semaphore(workerPermits.requirePositiveNumber("workerPermits"), true),
            DatabaseLane.SSE_MAINTENANCE to
                Semaphore(sseMaintenancePermits.requirePositiveNumber("sseMaintenancePermits"), true),
        )
    private val heldLane = ThreadLocal<DatabaseLane?>()

    init {
        require(!acquireTimeout.isNegative && !acquireTimeout.isZero) {
            "acquireTimeout must be positive"
        }
    }

    fun <T> withPermit(
        lane: DatabaseLane,
        block: () -> T,
    ): T {
        check(heldLane.get() == null) { "nested database permit acquisition is forbidden" }
        val semaphore = semaphores.getValue(lane)
        val acquired =
            try {
                semaphore.tryAcquire(acquireTimeout.toNanos(), TimeUnit.NANOSECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw DatabasePermitRejected(RETRY_AFTER, interrupted)
            }
        if (!acquired) {
            log.debug { "voucher_db_permit_rejected lane=$lane" }
            throw DatabasePermitRejected(RETRY_AFTER)
        }

        heldLane.set(lane)
        return try {
            block()
        } finally {
            heldLane.remove()
            semaphore.release()
            log.debug { "voucher_db_permit_released lane=$lane" }
        }
    }

    fun requireHeld() {
        check(heldLane.get() != null) { "JDBC access requires a database permit" }
    }

    companion object : KLogging() {
        private val RETRY_AFTER: Duration = Duration.ofSeconds(1)
    }
}
