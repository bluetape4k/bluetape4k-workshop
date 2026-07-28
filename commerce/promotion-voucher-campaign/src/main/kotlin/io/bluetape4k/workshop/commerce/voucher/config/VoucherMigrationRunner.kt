package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import org.springframework.core.io.Resource
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource

internal data class VoucherMigration(
    val version: String,
    val resource: Resource,
) {
    init {
        require(version.isNotBlank()) { "migration version must not be blank" }
    }

    fun read(): MigrationScript {
        val bytes =
            try {
                resource.inputStream.use { it.readAllBytes() }
            } catch (failure: Exception) {
                throw VoucherMigrationException(VoucherMigrationFailureCode.RESOURCE_UNAVAILABLE, failure)
            }
        return MigrationScript(
            version = version,
            checksum = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(),
            sql = bytes.toString(Charsets.UTF_8),
        )
    }
}

internal data class MigrationScript(
    val version: String,
    val checksum: String,
    val sql: String,
)

internal enum class VoucherMigrationResult {
    APPLIED,
    ALREADY_APPLIED,
}

internal enum class VoucherMigrationFailureCode {
    LOCK_TIMEOUT,
    CHECKSUM_DRIFT,
    STATEMENT_FAILED,
    RESOURCE_UNAVAILABLE,
}

internal class VoucherMigrationException(
    val code: VoucherMigrationFailureCode,
    cause: Throwable? = null,
) : RuntimeException(code.name, cause)

/** application-owned schema를 PostgreSQL transaction advisory lock 아래에서 정확히 한 번 적용합니다. */
internal class VoucherMigrationRunner(
    private val dataSource: DataSource,
    private val migration: VoucherMigration,
    private val advisoryLockKey: Long,
    private val lockTimeout: Duration = Duration.ofSeconds(30),
) {
    init {
        require(!lockTimeout.isNegative && !lockTimeout.isZero) { "lockTimeout must be positive" }
    }

    fun migrate(): VoucherMigrationResult {
        val script = migration.read()
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                acquireMigrationLock(connection)
                createHistoryTable(connection)
                when (val appliedChecksum = appliedChecksum(connection, script.version)) {
                    null -> applyMigration(connection, script)
                    script.checksum -> {
                        connection.commit()
                        log.info { "voucher_migration_already_applied version=${script.version}" }
                        VoucherMigrationResult.ALREADY_APPLIED
                    }

                    else -> throw VoucherMigrationException(VoucherMigrationFailureCode.CHECKSUM_DRIFT)
                }
            } catch (failure: VoucherMigrationException) {
                connection.rollbackQuietly()
                log.warn { "voucher_migration_failed version=${script.version} code=${failure.code}" }
                throw failure
            } catch (failure: SQLException) {
                connection.rollbackQuietly()
                log.warn {
                    "voucher_migration_failed version=${script.version} " +
                        "code=${VoucherMigrationFailureCode.STATEMENT_FAILED}"
                }
                throw VoucherMigrationException(VoucherMigrationFailureCode.STATEMENT_FAILED, failure)
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
                    throw VoucherMigrationException(VoucherMigrationFailureCode.LOCK_TIMEOUT)
                }
                LockSupport.parkNanos(LOCK_RETRY_DELAY.toNanos())
            }
        }
    }

    private fun createHistoryTable(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS voucher_schema_history (
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
        connection.prepareStatement(
            "SELECT checksum FROM voucher_schema_history WHERE version = ?",
        ).use { statement ->
            statement.setString(1, version)
            statement.executeQuery().use { result ->
                if (result.next()) result.getString("checksum") else null
            }
        }

    private fun applyMigration(
        connection: Connection,
        script: MigrationScript,
    ): VoucherMigrationResult {
        connection.createStatement().use { statement ->
            script.sql
                .split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach(statement::execute)
        }
        connection.prepareStatement(
            "INSERT INTO voucher_schema_history(version, checksum) VALUES (?, ?)",
        ).use { statement ->
            statement.setString(1, script.version)
            statement.setString(2, script.checksum)
            statement.executeUpdate()
        }
        connection.commit()
        log.info { "voucher_migration_applied version=${script.version}" }
        return VoucherMigrationResult.APPLIED
    }

    private fun Connection.rollbackQuietly() {
        runCatching { rollback() }
    }

    companion object : KLogging() {
        private val LOCK_RETRY_DELAY: Duration = Duration.ofMillis(10)
    }
}
