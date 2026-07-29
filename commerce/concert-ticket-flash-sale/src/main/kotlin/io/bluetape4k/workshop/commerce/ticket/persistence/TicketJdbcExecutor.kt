package io.bluetape4k.workshop.commerce.ticket.persistence

import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serial
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** 모든 ticket transaction에 적용되는 전역 row-lock 획득 순서입니다. */
enum class TicketLockRank {
    SALE,
    IDEMPOTENCY,
    USER_GUARD,
    IP_GUARD,
    BUYER,
    INVENTORY,
    ATTEMPT_ORDER,
    EFFECT,
}

/** 역순 deadlock을 만들 수 있는 code path를 거부합니다. */
class TicketLockOrderViolation(
    val previous: TicketLockRank,
    val requested: TicketLockRank,
) : IllegalStateException("ticket_lock_order_violation:$previous:$requested") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** JDBC connection을 얻기 전 database work를 거부합니다. */
class TicketDatabasePermitUnavailable : IllegalStateException("ticket_database_permit_unavailable") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** lock 획득 순서를 기록하는 단일 Exposed transaction입니다. */
class TicketJdbcTransaction internal constructor(
    val exposed: JdbcTransaction,
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

/** Exposed JDBC transaction을 열기 전에 bounded permit을 획득합니다. */
class TicketJdbcExecutor(
    dataSource: DataSource,
    foregroundPermits: Int,
    internal val permitTimeout: Duration = Duration.ofMillis(250),
) {
    private val permits = Semaphore(foregroundPermits, true)
    private val database = Database.connect(
        datasource = dataSource,
        databaseConfig = DatabaseConfig {
            defaultMaxAttempts = 1
        },
    )

    init {
        foregroundPermits.requirePositiveNumber("foregroundPermits")
        permitTimeout.requireGt(Duration.ZERO, "permitTimeout")
    }

    fun <T> transaction(block: TicketJdbcTransaction.() -> T): T {
        if (!permits.tryAcquire(permitTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw TicketDatabasePermitUnavailable()
        }
        try {
            return transaction(database) {
                TicketJdbcTransaction(this).block()
            }
        } finally {
            permits.release()
        }
    }
}
