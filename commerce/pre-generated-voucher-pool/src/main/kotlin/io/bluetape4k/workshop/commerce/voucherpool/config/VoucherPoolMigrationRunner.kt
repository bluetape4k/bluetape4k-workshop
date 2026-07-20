@file:Suppress("MagicNumber", "MaxLineLength", "NestedBlockDepth", "ThrowsCount", "TooGenericExceptionCaught")

package io.bluetape4k.workshop.commerce.voucherpool.config

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
                    checksum == null -> apply(connection, script).also {
                        log.info { "voucher_pool_migration_applied version=${script.version}" }
                    }
                    checksum == script.checksum -> {
                        connection.commit()
                        log.info { "voucher_pool_migration_already_applied version=${script.version}" }
                        VoucherPoolMigrationResult.ALREADY_APPLIED
                    }
                    else -> throw VoucherPoolMigrationException(VoucherPoolMigrationFailureCode.CHECKSUM_DRIFT)
                }
            } catch (failure: VoucherPoolMigrationException) {
                runCatching { connection.rollback() }
                log.warn(failure) {
                    "voucher_pool_migration_failed version=${script.version} code=${failure.code}"
                }
                throw failure
            } catch (failure: SQLException) {
                runCatching { connection.rollback() }
                log.warn(failure) {
                    "voucher_pool_migration_failed version=${script.version} code=${VoucherPoolMigrationFailureCode.STATEMENT_FAILED}"
                }
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
            splitSqlStatements(script.sql).forEach(statement::execute)
        }
        connection.prepareStatement("INSERT INTO voucher_pool_schema_history(version,checksum) VALUES (?,?)").use {
            it.setString(1, script.version); it.setString(2, script.checksum); it.executeUpdate()
        }
        connection.commit()
        return VoucherPoolMigrationResult.APPLIED
    }

    companion object : KLogging()

    @Suppress("LongMethod", "CyclomaticComplexMethod") // The scanner keeps PostgreSQL lexical states explicit.
    private fun splitSqlStatements(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        var singleQuoted = false
        var doubleQuoted = false
        var lineComment = false
        var blockCommentDepth = 0
        var dollarTag: String? = null
        while (index < sql.length) {
            val next = sql.getOrNull(index + 1)
            val activeDollarTag = dollarTag
            when {
                activeDollarTag != null -> {
                    if (sql.startsWith(activeDollarTag, index)) {
                        current.append(activeDollarTag)
                        index += activeDollarTag.length
                        dollarTag = null
                    } else {
                        current.append(sql[index++])
                    }
                }

                lineComment -> {
                    current.append(sql[index])
                    if (sql[index++] == '\n') lineComment = false
                }

                blockCommentDepth > 0 -> {
                    when {
                        sql[index] == '/' && next == '*' -> {
                            current.append("/*")
                            blockCommentDepth++
                            index += 2
                        }
                        sql[index] == '*' && next == '/' -> {
                            current.append("*/")
                            blockCommentDepth--
                            index += 2
                        }
                        else -> current.append(sql[index++])
                    }
                }

                singleQuoted -> {
                    current.append(sql[index])
                    if (sql[index] == '\'' && next == '\'') {
                        current.append(next)
                        index += 2
                    } else {
                        if (sql[index] == '\'') singleQuoted = false
                        index++
                    }
                }

                doubleQuoted -> {
                    current.append(sql[index])
                    if (sql[index] == '"' && next == '"') {
                        current.append(next)
                        index += 2
                    } else {
                        if (sql[index] == '"') doubleQuoted = false
                        index++
                    }
                }

                sql[index] == '-' && next == '-' -> {
                    current.append("--")
                    lineComment = true
                    index += 2
                }

                sql[index] == '/' && next == '*' -> {
                    current.append("/*")
                    blockCommentDepth = 1
                    index += 2
                }

                sql[index] == '\'' -> {
                    current.append(sql[index++])
                    singleQuoted = true
                }

                sql[index] == '"' -> {
                    current.append(sql[index++])
                    doubleQuoted = true
                }

                sql[index] == '$' -> {
                    val tagEnd = sql.indexOf('$', startIndex = index + 1)
                    val tag = tagEnd.takeIf { it >= 0 }?.let { sql.substring(index, it + 1) }
                    if (tag != null && tag.drop(1).dropLast(1).all { it == '_' || it.isLetterOrDigit() }) {
                        current.append(tag)
                        dollarTag = tag
                        index += tag.length
                    } else {
                        current.append(sql[index++])
                    }
                }

                sql[index] == ';' -> {
                    current.toString().trim().takeIf(String::isNotEmpty)?.let(statements::add)
                    current.clear()
                    index++
                }

                else -> current.append(sql[index++])
            }
        }
        check(!singleQuoted && !doubleQuoted && blockCommentDepth == 0 && dollarTag == null) {
            "unterminated SQL literal or comment"
        }
        current.toString().trim().takeIf(String::isNotEmpty)?.let(statements::add)
        return statements
    }
}
