package io.bluetape4k.workshop.commerce.reservation.redis

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

enum class DatabaseWorkload { FOREGROUND, BACKGROUND }

sealed interface DatabaseBulkheadOutcome<out T> : Serializable {
    data class Executed<out T>(
        val value: T,
    ) : DatabaseBulkheadOutcome<T> {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class Rejected(
        val workload: DatabaseWorkload,
    ) : DatabaseBulkheadOutcome<Nothing> {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}

/**
 * Protects the bounded JDBC pool independently of Redis health.
 *
 * Foreground commands and background workers deliberately use separate permits so a sweep cannot
 * consume the connection headroom reserved for interactive traffic.
 */
class NodeLocalDatabaseBulkhead(
    foregroundPermits: Int = 5,
    backgroundPermits: Int = 1,
    private val acquireTimeout: Duration = Duration.ofMillis(100),
) {
    companion object : KLogging()

    private val foreground = Semaphore(foregroundPermits.requirePositiveNumber("foregroundPermits"), true)
    private val background = Semaphore(backgroundPermits.requirePositiveNumber("backgroundPermits"), true)

    init {
        require(!acquireTimeout.isNegative) { "acquireTimeout must not be negative" }
    }

    fun <T> execute(
        workload: DatabaseWorkload,
        action: () -> T,
    ): DatabaseBulkheadOutcome<T> {
        val semaphore =
            when (workload) {
                DatabaseWorkload.FOREGROUND -> foreground
                DatabaseWorkload.BACKGROUND -> background
            }
        val acquired =
            try {
                semaphore.tryAcquire(acquireTimeout.toNanos(), TimeUnit.NANOSECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
        if (!acquired) {
            log.debug { "reservation_db_bulkhead_rejected workload=$workload" }
            return DatabaseBulkheadOutcome.Rejected(workload)
        }

        return try {
            DatabaseBulkheadOutcome.Executed(action())
        } finally {
            semaphore.release()
            log.debug { "reservation_db_bulkhead_released workload=$workload" }
        }
    }
}
