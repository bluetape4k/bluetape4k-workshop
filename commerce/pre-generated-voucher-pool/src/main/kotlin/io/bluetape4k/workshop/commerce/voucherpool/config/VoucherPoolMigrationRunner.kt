@file:Suppress("MagicNumber", "MaxLineLength", "NestedBlockDepth", "ThrowsCount", "TooGenericExceptionCaught")

package io.bluetape4k.workshop.commerce.voucherpool.config

import org.springframework.core.io.Resource
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource

internal data class VoucherPoolMigration(val version: String, val resource: Resource) {
    init { require(version.isNotBlank()) { "migration version must not be blank" } }

    fun read(): VoucherPoolMigrationScript {
        val bytes = try {
            resource.inputStream.use { it.readAllBytes() }
        } catch (failure: Exception) {
            throw VoucherPoolMigrationException(VoucherPoolMigrationFailureCode.RESOURCE_UNAVAILABLE, failure)
        }
        return VoucherPoolMigrationScript(version, MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(), bytes.toString(Charsets.UTF_8))
    }
}

internal data class VoucherPoolMigrationScript(val version: String, val checksum: String, val sql: String)

internal enum class VoucherPoolMigrationResult { APPLIED, ALREADY_APPLIED }

internal enum class VoucherPoolMigrationFailureCode { LOCK_TIMEOUT, CHECKSUM_DRIFT, STATEMENT_FAILED, RESOURCE_UNAVAILABLE }

internal class VoucherPoolMigrationException(
    val code: VoucherPoolMigrationFailureCode,
    cause: Throwable? = null,
) : RuntimeException(code.name, cause)

/** Applies versioned SQL under a transaction advisory lock and fails closed on checksum drift. */
internal class VoucherPoolMigrationRunner(
    private val dataSource: DataSource,
    private val migration: VoucherPoolMigration,
    private val advisoryLockKey: Long,
    private val lockTimeout: Duration = Duration.ofSeconds(30),
) {
    init { require(!lockTimeout.isNegative && !lockTimeout.isZero) { "lockTimeout must be positive" } }

    fun migrate(): VoucherPoolMigrationResult {
        val script = migration.read()
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                acquireLock(connection)
                connection.createStatement().execute(
                    """CREATE TABLE IF NOT EXISTS voucher_pool_schema_history(
                        version VARCHAR(64) PRIMARY KEY, checksum VARCHAR(64) NOT NULL,
                        applied_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp())""",
                )
                val checksum = appliedChecksum(connection, script.version)
                when {
                    checksum == null -> apply(connection, script)
                    checksum == script.checksum -> {
                        connection.commit()
                        VoucherPoolMigrationResult.ALREADY_APPLIED
                    }
                    else -> throw VoucherPoolMigrationException(VoucherPoolMigrationFailureCode.CHECKSUM_DRIFT)
                }
            } catch (failure: VoucherPoolMigrationException) {
                runCatching { connection.rollback() }
                throw failure
            } catch (failure: SQLException) {
                runCatching { connection.rollback() }
                throw VoucherPoolMigrationException(VoucherPoolMigrationFailureCode.STATEMENT_FAILED, failure)
            }
        }
    }

    private fun acquireLock(connection: Connection) {
        val deadline = System.nanoTime() + lockTimeout.toNanos()
        connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, advisoryLockKey)
            while (true) {
                statement.executeQuery().use { result -> if (result.next() && result.getBoolean(1)) return }
                if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted) {
                    throw VoucherPoolMigrationException(VoucherPoolMigrationFailureCode.LOCK_TIMEOUT)
                }
                LockSupport.parkNanos(Duration.ofMillis(10).toNanos())
            }
        }
    }

    private fun appliedChecksum(connection: Connection, version: String): String? =
        connection.prepareStatement("SELECT checksum FROM voucher_pool_schema_history WHERE version=?").use { statement ->
            statement.setString(1, version)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }

    private fun apply(connection: Connection, script: VoucherPoolMigrationScript): VoucherPoolMigrationResult {
        connection.createStatement().use { statement ->
            script.sql.split(';').map(String::trim).filter(String::isNotEmpty).forEach(statement::execute)
        }
        connection.prepareStatement("INSERT INTO voucher_pool_schema_history(version,checksum) VALUES (?,?)").use {
            it.setString(1, script.version); it.setString(2, script.checksum); it.executeUpdate()
        }
        connection.commit()
        return VoucherPoolMigrationResult.APPLIED
    }
}
