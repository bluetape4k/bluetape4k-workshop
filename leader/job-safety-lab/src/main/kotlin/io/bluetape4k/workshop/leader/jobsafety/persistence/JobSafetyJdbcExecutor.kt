package io.bluetape4k.workshop.leader.jobsafety.persistence

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

class JobSafetyDatabasePermitUnavailable : IllegalStateException("job_safety_database_permit_unavailable")

class JobSafetyJdbcTransaction internal constructor(
    val exposed: JdbcTransaction,
) {
    fun <T> withExposed(block: JdbcTransaction.() -> T): T = exposed.block()
}

class JobSafetyJdbcExecutor(
    dataSource: DataSource,
    foregroundPermits: Int = 8,
    private val permitTimeout: Duration = Duration.ofMillis(250),
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
        TransactionManager.defaultDatabase = database
    }

    fun <T> transaction(block: JobSafetyJdbcTransaction.() -> T): T {
        check(!closed.get()) { "job_safety_jdbc_executor_closed" }
        if (!permits.tryAcquire(permitTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw JobSafetyDatabasePermitUnavailable()
        }
        try {
            return transaction(database) {
                JobSafetyJdbcTransaction(this).block()
            }
        } finally {
            permits.release()
        }
    }

    /** Spring이 DataSource를 닫기 전에 Exposed의 process-wide registration을 제거합니다. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            TransactionManager.closeAndUnregister(database)
        }
    }
}
