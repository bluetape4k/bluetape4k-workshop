package io.bluetape4k.workshop.leader.jobsafety.persistence

import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
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
) {
    private val permits = Semaphore(foregroundPermits, true)
    private val database =
        Database.connect(
            datasource = dataSource,
            databaseConfig = DatabaseConfig { defaultMaxAttempts = 1 },
        )

    init {
        foregroundPermits.requirePositiveNumber("foregroundPermits")
        permitTimeout.requireGt(Duration.ZERO, "permitTimeout")
    }

    fun <T> transaction(block: JobSafetyJdbcTransaction.() -> T): T {
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
}
