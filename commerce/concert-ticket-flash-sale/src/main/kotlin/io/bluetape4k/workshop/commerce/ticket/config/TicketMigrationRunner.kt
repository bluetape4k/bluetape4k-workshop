package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import org.springframework.core.io.Resource
import java.io.Serial
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource

/** One versioned, checksummed schema resource. */
data class TicketMigration(
    val version: String,
    val resource: Resource,
) {
    init {
        require(version.isNotBlank()) { "migration version must not be blank" }
    }

    fun read(): TicketMigrationScript {
        val bytes =
            try {
                resource.inputStream.use { it.readAllBytes() }
            } catch (failure: Exception) {
                throw TicketMigrationException(TicketMigrationFailure.RESOURCE_UNAVAILABLE, failure)
            }
        return TicketMigrationScript(
            version = version,
            checksum = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(),
            sql = bytes.toString(Charsets.UTF_8),
        )
    }
}

/** Materialized migration input used inside one database transaction. */
data class TicketMigrationScript(
    val version: String,
    val checksum: String,
    val sql: String,
)

/** Result of applying or replaying one migration. */
enum class TicketMigrationResult {
    APPLIED,
    ALREADY_APPLIED,
}

/** Sanitized migration failure categories. */
enum class TicketMigrationFailure {
    LOCK_TIMEOUT,
    CHECKSUM_MISMATCH,
    STATEMENT_FAILED,
    RESOURCE_UNAVAILABLE,
}

/** Migration failure safe for startup diagnostics. */
class TicketMigrationException(
    val code: TicketMigrationFailure,
    cause: Throwable? = null,
) : IllegalStateException(code.name, cause) {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Applies an application-owned migration under a PostgreSQL transaction advisory lock. */
class TicketMigrationRunner(
    private val dataSource: DataSource,
    private val migration: TicketMigration,
    private val advisoryLockKey: Long,
    private val lockTimeout: Duration = Duration.ofSeconds(30),
) {
    init {
        require(lockTimeout.isPositive) { "lockTimeout must be positive" }
    }

    fun migrate(): TicketMigrationResult {
        val script = migration.read()
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                acquireMigrationLock(connection)
                createHistoryTable(connection)
                when (val checksum = appliedChecksum(connection, script.version)) {
                    null -> applyMigration(connection, script)
                    script.checksum -> {
                        connection.commit()
                        log.info { "ticket_migration_already_applied version=${script.version}" }
                        TicketMigrationResult.ALREADY_APPLIED
                    }

                    else -> throw TicketMigrationException(TicketMigrationFailure.CHECKSUM_MISMATCH)
                }
            } catch (failure: TicketMigrationException) {
                connection.rollbackQuietly()
                log.warn { "ticket_migration_failed version=${script.version} code=${failure.code}" }
                throw failure
            } catch (failure: SQLException) {
                connection.rollbackQuietly()
                log.warn {
                    "ticket_migration_failed version=${script.version} " +
                        "code=${TicketMigrationFailure.STATEMENT_FAILED}"
                }
                throw TicketMigrationException(TicketMigrationFailure.STATEMENT_FAILED, failure)
            }
        }
    }

    private fun acquireMigrationLock(connection: Connection) {
        val deadline = System.nanoTime() + lockTimeout.toNanos()
        connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, advisoryLockKey)
            while (true) {
                statement.executeQuery().use { result ->
                    check(result.next()) { "advisory lock query returned no row" }
                    if (result.getBoolean(1)) return
                }
                if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted) {
                    throw TicketMigrationException(TicketMigrationFailure.LOCK_TIMEOUT)
                }
                LockSupport.parkNanos(LOCK_RETRY_DELAY.toNanos())
            }
        }
    }

    private fun createHistoryTable(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS ticket_schema_history (
                    version VARCHAR(64) PRIMARY KEY,
                    checksum VARCHAR(64) NOT NULL,
                    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.trimIndent(),
            )
        }
    }

    private fun appliedChecksum(
        connection: Connection,
        version: String,
    ): String? =
        connection.prepareStatement("SELECT checksum FROM ticket_schema_history WHERE version = ?").use { statement ->
            statement.setString(1, version)
            statement.executeQuery().use { result ->
                if (result.next()) result.getString("checksum") else null
            }
        }

    private fun applyMigration(
        connection: Connection,
        script: TicketMigrationScript,
    ): TicketMigrationResult {
        connection.createStatement().use { statement ->
            script.sql
                .split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach(statement::execute)
        }
        connection.prepareStatement(
            "INSERT INTO ticket_schema_history(version, checksum) VALUES (?, ?)",
        ).use { statement ->
            statement.setString(1, script.version)
            statement.setString(2, script.checksum)
            statement.executeUpdate()
        }
        connection.commit()
        log.info { "ticket_migration_applied version=${script.version}" }
        return TicketMigrationResult.APPLIED
    }

    private fun Connection.rollbackQuietly() {
        runCatching { rollback() }
    }

    companion object : KLogging() {
        private val LOCK_RETRY_DELAY: Duration = Duration.ofMillis(10)
    }
}
