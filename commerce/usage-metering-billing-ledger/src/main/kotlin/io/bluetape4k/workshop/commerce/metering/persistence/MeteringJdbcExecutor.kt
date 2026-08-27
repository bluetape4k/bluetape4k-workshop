package io.bluetape4k.workshop.commerce.metering.persistence

import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

private const val DEFAULT_PERMIT_TIMEOUT_MILLIS = 250L
private val DEFAULT_PERMIT_TIMEOUT: Duration = Duration.ofMillis(DEFAULT_PERMIT_TIMEOUT_MILLIS)

class MeteringDatabasePermitUnavailable : IllegalStateException("metering_database_permit_unavailable")

class MeteringJdbcTransaction internal constructor(
    val exposed: JdbcTransaction,
) {
    fun <T> withExposed(block: JdbcTransaction.() -> T): T = exposed.block()
}

class MeteringJdbcExecutor(
    dataSource: DataSource,
    foregroundPermits: Int = 8,
    private val permitTimeout: Duration = DEFAULT_PERMIT_TIMEOUT,
) : AutoCloseable {
    private val permits = Semaphore(foregroundPermits, true)
    private val closed = AtomicBoolean()
    private val database =
        Database.connect(
            datasource = dataSource,
            databaseConfig = DatabaseConfig { defaultMaxAttempts = 1 },
        )

    init {
        foregroundPermits.requirePositiveNumber("foregroundPermits")
        permitTimeout.requireGt(Duration.ZERO, "permitTimeout")
    }

    fun <T> transaction(block: MeteringJdbcTransaction.() -> T): T {
        check(!closed.get()) { "metering_jdbc_executor_closed" }
        if (!permits.tryAcquire(permitTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw MeteringDatabasePermitUnavailable()
        }
        try {
            return transaction(database) {
                MeteringJdbcTransaction(this).block()
            }
        } finally {
            permits.release()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            TransactionManager.closeAndUnregister(database)
        }
    }
}
