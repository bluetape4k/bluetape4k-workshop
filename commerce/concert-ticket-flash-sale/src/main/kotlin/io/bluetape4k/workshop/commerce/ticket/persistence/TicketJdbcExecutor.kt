package io.bluetape4k.workshop.commerce.ticket.persistence

import java.io.Serial
import java.sql.Connection
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** Global row-lock acquisition order for all ticket transactions. */
enum class TicketLockRank {
    IDEMPOTENCY,
    USER_GUARD,
    IP_GUARD,
    BUYER,
    INVENTORY,
    ATTEMPT_ORDER,
    EFFECT,
}

/** Rejects code paths that could create a reverse-order deadlock. */
class TicketLockOrderViolation(
    val previous: TicketLockRank,
    val requested: TicketLockRank,
) : IllegalStateException("ticket_lock_order_violation:$previous:$requested") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Rejects database work before a JDBC connection is acquired. */
class TicketDatabasePermitUnavailable : IllegalStateException("ticket_database_permit_unavailable") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** One connection-bound transaction that records lock acquisition order. */
class TicketJdbcTransaction internal constructor(
    val connection: Connection,
) {
    private var lastRank: TicketLockRank? = null

    fun acquire(rank: TicketLockRank) {
        val previous = lastRank
        if (previous != null && rank < previous) {
            throw TicketLockOrderViolation(previous, rank)
        }
        lastRank = rank
    }
}

/** Acquires a bounded permit before opening a JDBC transaction. */
class TicketJdbcExecutor(
    private val dataSource: DataSource,
    foregroundPermits: Int,
    private val permitTimeout: Duration = Duration.ofMillis(250),
) {
    private val permits = Semaphore(foregroundPermits, true)

    init {
        require(foregroundPermits > 0) { "foregroundPermits must be positive" }
        require(permitTimeout.isPositive) { "permitTimeout must be positive" }
    }

    fun <T> transaction(block: TicketJdbcTransaction.() -> T): T {
        if (!permits.tryAcquire(permitTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw TicketDatabasePermitUnavailable()
        }
        try {
            return dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val result = TicketJdbcTransaction(connection).block()
                    connection.commit()
                    result
                } catch (failure: Throwable) {
                    runCatching { connection.rollback() }
                    throw failure
                }
            }
        } finally {
            permits.release()
        }
    }
}
