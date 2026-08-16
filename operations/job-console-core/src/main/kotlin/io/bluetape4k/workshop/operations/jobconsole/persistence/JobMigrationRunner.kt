package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource

data class JobMigration(
    val version: String,
    val bytes: ByteArray,
) {
    init {
        require(version.isNotBlank()) { "migration version must not be blank" }
    }

    val checksum: String
        get() = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    val sql: String
        get() = bytes.toString(Charsets.UTF_8)

    companion object {
        fun classpath(version: String, resource: String): JobMigration {
            val bytes =
                requireNotNull(JobMigration::class.java.classLoader.getResourceAsStream(resource)) {
                    "Migration resource is unavailable"
                }.use { it.readAllBytes() }
            return JobMigration(version, bytes)
        }
    }
}

enum class JobMigrationResult {
    APPLIED,
    ALREADY_APPLIED,
}

enum class JobMigrationFailureCode {
    CHECKSUM_DRIFT,
    STATEMENT_FAILED,
}

class JobMigrationException(
    val code: JobMigrationFailureCode,
    cause: Throwable? = null,
) : RuntimeException(code.name, cause)

class JobMigrationRunner(
    private val dataSource: DataSource,
    private val migrations: List<JobMigration>,
    private val advisoryLockKey: Long,
    private val lockTimeout: Duration = Duration.ofSeconds(2),
    private val statementTimeout: Duration = Duration.ofSeconds(30),
    private val logRowCounts: Boolean = true,
) {
    init {
        require(migrations.map(JobMigration::version).distinct().size == migrations.size) {
            "migration versions must be unique"
        }
        require(!lockTimeout.isZero && !lockTimeout.isNegative) { "lockTimeout must be positive" }
        require(!statementTimeout.isZero && !statementTimeout.isNegative) { "statementTimeout must be positive" }
    }

    fun migrate(): List<JobMigrationResult> =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                setLocalTimeouts(connection)
                acquireLock(connection)
                createHistory(connection)
                migrations.sortedBy(JobMigration::version).map { migration -> apply(connection, migration) }
                    .also {
                        connection.commit()
                    }
            } catch (failure: JobMigrationException) {
                connection.rollbackQuietly()
                log.warn { "job_console_migration_failed code=${failure.code}" }
                throw failure
            } catch (failure: SQLException) {
                connection.rollbackQuietly()
                log.warn { "job_console_migration_failed code=${JobMigrationFailureCode.STATEMENT_FAILED}" }
                throw JobMigrationException(JobMigrationFailureCode.STATEMENT_FAILED, failure)
            }
        }

    private fun acquireLock(connection: Connection) {
        connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, advisoryLockKey)
            statement.executeQuery().use { result -> check(result.next()) }
        }
    }

    private fun setLocalTimeouts(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("SET LOCAL lock_timeout = '${lockTimeout.toMillis()}ms'")
            statement.execute("SET LOCAL statement_timeout = '${statementTimeout.toMillis()}ms'")
        }
    }

    private fun createHistory(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS job_schema_history (
                    version VARCHAR(64) PRIMARY KEY,
                    checksum CHAR(64) NOT NULL,
                    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.trimIndent(),
            )
        }
    }

    private fun apply(connection: Connection, migration: JobMigration): JobMigrationResult {
        val rowCountBefore = if (logRowCounts && migration.version == "002") countJobRequests(connection) else null
        val appliedChecksum =
            connection.prepareStatement("SELECT checksum FROM job_schema_history WHERE version = ?").use { statement ->
                statement.setString(1, migration.version)
                statement.executeQuery().use { result -> if (result.next()) result.getString(1).trim() else null }
            }
        if (appliedChecksum != null) {
            if (appliedChecksum != migration.checksum) {
                throw JobMigrationException(JobMigrationFailureCode.CHECKSUM_DRIFT)
            }
            logRowCount(migration, rowCountBefore, countJobRequests(connection))
            return JobMigrationResult.ALREADY_APPLIED
        }

        connection.createStatement().use { statement ->
            migration.sql
                .lineSequence()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
                .split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach(statement::execute)
        }
        connection.prepareStatement("INSERT INTO job_schema_history(version, checksum) VALUES (?, ?)").use { statement ->
            statement.setString(1, migration.version)
            statement.setString(2, migration.checksum)
            statement.executeUpdate()
        }
        log.info { "job_console_migration_applied version=${migration.version}" }
        logRowCount(migration, rowCountBefore, countJobRequests(connection))
        return JobMigrationResult.APPLIED
    }

    private fun countJobRequests(connection: Connection): Long? =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT CASE
                    WHEN to_regclass('job_requests') IS NULL THEN NULL
                    ELSE (SELECT count(*) FROM job_requests)
                END
                """.trimIndent(),
            ).use { result ->
                check(result.next())
                result.getLong(1).takeUnless { result.wasNull() }
            }
        }

    private fun logRowCount(migration: JobMigration, before: Long?, after: Long?) {
        if (before != null && after != null) {
            log.info {
                "job_console_migration_row_count version=${migration.version} before=$before after=$after"
            }
        }
    }

    private fun Connection.rollbackQuietly() {
        runCatching { rollback() }
    }

    companion object : KLogging()
}
