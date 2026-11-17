@file:Suppress("MagicNumber", "MaxLineLength", "NestedBlockDepth", "ThrowsCount", "TooGenericExceptionCaught")

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAuditTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCampaignTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCodeDedupTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCommandTombstoneTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolEntryTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRevokePreviewGrantTable
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKekRing
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKeyMaterialUnavailableException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.core.io.Resource
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource

internal data class VoucherPoolMigration(val version: String, val resource: Resource) {
    init {
        version.requireNotBlank("version")
    }

    fun read(): VoucherPoolMigrationScript {
        val bytes = try {
            resource.inputStream.use { it.readAllBytes() }
        } catch (failure: Exception) {
            throw VoucherPoolMigrationException(VoucherPoolMigrationFailureCode.RESOURCE_UNAVAILABLE, failure)
        }

        return VoucherPoolMigrationScript(
            version = version,
            checksum = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(),
            sql = bytes.toUtf8String()
        )
    }
}

internal data class VoucherPoolMigrationScript(
    val version: String,
    val checksum: String,
    val sql: String,
)

internal enum class VoucherPoolMigrationResult {
    APPLIED,
    ALREADY_APPLIED
}

internal enum class VoucherPoolMigrationFailureCode {
    LOCK_TIMEOUT,
    CHECKSUM_DRIFT,
    STATEMENT_FAILED,
    RESOURCE_UNAVAILABLE,
    CONNECTION_UNAVAILABLE,
}

internal class VoucherPoolMigrationException(
    val code: VoucherPoolMigrationFailureCode,
    cause: Throwable? = null,
): RuntimeException(code.name, cause)

/** versioned SQL을 transaction advisory lock 아래에서 적용하고 checksum drift가 있으면 fail-closed합니다. */
@Suppress("SqlResolve")
internal class VoucherPoolMigrationRunner(
    private val dataSource: DataSource,
    private val migration: VoucherPoolMigration,
    private val advisoryLockKey: Long,
    private val lockTimeout: Duration = 30.seconds(),
) {
    init {
        require(!lockTimeout.isNegative && !lockTimeout.isZero) { "lockTimeout must be positive" }
    }

    fun migrate(): VoucherPoolMigrationResult =
        try {
            migrate(migration.read())
        } catch (failure: VoucherPoolMigrationException) {
            log.warn(failure) {
                "voucher_pool_migration_failed version=${migration.version} code=${failure.code}"
            }
            throw failure
        } catch (failure: SQLException) {
            val mapped = VoucherPoolMigrationException(VoucherPoolMigrationFailureCode.CONNECTION_UNAVAILABLE, failure)
            log.warn(mapped) {
                "voucher_pool_migration_failed version=${migration.version} code=${mapped.code}"
            }
            throw mapped
        }

    private fun migrate(script: VoucherPoolMigrationScript): VoucherPoolMigrationResult =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                acquireLock(connection)
                connection.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS voucher_pool_schema_history(
                            version VARCHAR(64) PRIMARY KEY, 
                            checksum VARCHAR(64) NOT NULL,
                            applied_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp()
                        )
                        """.trimIndent(),

                    )
                }
                when (appliedChecksum(connection, script.version)) {
                    null            ->
                        apply(connection, script).also {
                            log.info { "voucher_pool_migration_applied version=${script.version}" }
                        }

                    script.checksum -> {
                        connection.commit()
                        log.info { "voucher_pool_migration_already_applied version=${script.version}" }
                        VoucherPoolMigrationResult.ALREADY_APPLIED
                    }

                    else            ->
                        throw VoucherPoolMigrationException(VoucherPoolMigrationFailureCode.CHECKSUM_DRIFT)
                }
            } catch (failure: VoucherPoolMigrationException) {
                runCatching { connection.rollback() }
                throw failure
            } catch (failure: SQLException) {
                runCatching { connection.rollback() }
                throw VoucherPoolMigrationException(VoucherPoolMigrationFailureCode.STATEMENT_FAILED, failure)
            }
        }

    private fun acquireLock(connection: Connection) {
        val deadline = System.nanoTime() + lockTimeout.toNanos()
        connection
            .prepareStatement("SELECT pg_try_advisory_xact_lock(?)")
            .use { stmt ->
                stmt.setLong(1, advisoryLockKey)
                while (true) {
                    stmt.executeQuery().use { result ->
                        if (result.next() && result.getBoolean(1)) return
                    }
                    if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted) {
                        throw VoucherPoolMigrationException(VoucherPoolMigrationFailureCode.LOCK_TIMEOUT)
                    }
                    LockSupport.parkNanos(10.millis().toNanos())
                }
            }
    }

    private fun appliedChecksum(connection: Connection, version: String): String? =
        connection
            .prepareStatement("SELECT checksum FROM voucher_pool_schema_history WHERE version=?")
            .use { stmt ->
                stmt.setString(1, version)
                stmt.executeQuery().use { result ->
                    if (result.next()) result.getString(1) else null
                }
            }

    private fun apply(connection: Connection, script: VoucherPoolMigrationScript): VoucherPoolMigrationResult {
        connection.createStatement().use { stmt ->
            splitSqlStatements(script.sql).forEach(stmt::execute)
        }
        connection
            .prepareStatement("INSERT INTO voucher_pool_schema_history(version,checksum) VALUES (?,?)")
            .use { stmt ->
                stmt.setString(1, script.version)
                stmt.setString(2, script.checksum)
                stmt.executeUpdate()
            }
        connection.commit()
        return VoucherPoolMigrationResult.APPLIED
    }

    companion object: KLogging()

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

                lineComment             -> {
                    current.append(sql[index])
                    if (sql[index++] == '\n') lineComment = false
                }

                blockCommentDepth > 0   -> {
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

                singleQuoted            -> {
                    current.append(sql[index])
                    if (sql[index] == '\'' && next == '\'') {
                        current.append(next)
                        index += 2
                    } else {
                        if (sql[index] == '\'') singleQuoted = false
                        index++
                    }
                }

                doubleQuoted            -> {
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

                sql[index] == '\''      -> {
                    current.append(sql[index++])
                    singleQuoted = true
                }

                sql[index] == '"'       -> {
                    current.append(sql[index++])
                    doubleQuoted = true
                }

                sql[index] == '$'       -> {
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

                sql[index] == ';'       -> {
                    current.toString().trim().takeIf(String::isNotEmpty)?.let(statements::add)
                    current.clear()
                    index++
                }

                else                    ->
                    current.append(sql[index++])
            }
        }
        check(!singleQuoted && !doubleQuoted && blockCommentDepth == 0 && dollarTag == null) {
            "unterminated SQL literal or comment"
        }
        current.toString().trim().takeIf(String::isNotEmpty)?.let(statements::add)

        return statements.also { parsedStatements ->
            parsedStatements.forEachIndexed { index, statementSql ->
                log.debug { "sql[$index]: $statementSql" }
            }
        }
    }
}

/** live PostgreSQL authority row가 참조하는 key version에 대한 bounded purpose-aware verification입니다. */
internal class VoucherPoolReferencedKeyPreflight(
    private val dataSource: DataSource,
    private val digests: VoucherDigestService,
    private val kekRing: VoucherKekRing,
) {
    private companion object: KLogging() {
        const val MAX_REFERENCED_VERSIONS = 64
    }

    fun verify() {
        withExposedTransaction {
            distinctStrings(VoucherPoolEntryTable.kekVersion) {
                VoucherPoolEntryTable.select(VoucherPoolEntryTable.kekVersion)
                    .where { VoucherPoolEntryTable.kekVersion.isNotNull() }
            }.forEach(kekRing::require)

            distinctNullableInts(VoucherPoolEntryTable.verificationKeyVersion) {
                VoucherPoolEntryTable.select(VoucherPoolEntryTable.verificationKeyVersion)
                    .where { VoucherPoolEntryTable.verificationKeyVersion.isNotNull() }
            }.forEach {
                digests.requireAvailable(DigestPurpose.VERIFICATION, it)
            }
            distinctInts(VoucherPoolCampaignTable.userIdentityKeyVersion) {
                VoucherPoolCampaignTable.select(VoucherPoolCampaignTable.userIdentityKeyVersion)
            }.forEach {
                digests.requireAvailable(DigestPurpose.USER_IDENTITY, it)
            }
            val auditVersions = buildList {
                addAll(distinctNullableInts(VoucherPoolAuditTable.auditKeyVersion) {
                    VoucherPoolAuditTable.select(VoucherPoolAuditTable.auditKeyVersion)
                        .where { VoucherPoolAuditTable.auditKeyVersion.isNotNull() }
                })
                addAll(distinctInts(VoucherPoolRevokePreviewGrantTable.signatureKeyVersion) {
                    VoucherPoolRevokePreviewGrantTable.select(VoucherPoolRevokePreviewGrantTable.signatureKeyVersion)
                })
            }.distinct().sorted().also(::requireBounded)
            auditVersions.forEach {
                digests.requireAvailable(DigestPurpose.AUDIT, it)
            }
            distinctInts(VoucherPoolCodeDedupTable.keyVersion) {
                VoucherPoolCodeDedupTable.select(VoucherPoolCodeDedupTable.keyVersion)
            }.forEach {
                digests.requireAvailable(DigestPurpose.STABLE_DEDUP, it)
            }
            distinctInts(VoucherPoolCommandTombstoneTable.keyVersion) {
                VoucherPoolCommandTombstoneTable.select(VoucherPoolCommandTombstoneTable.keyVersion)
            }.forEach {
                digests.requireAvailable(DigestPurpose.COMMAND_TOMBSTONE, it)
            }
        }
    }

    private fun distinctInts(column: org.jetbrains.exposed.v1.core.Column<Int>, query: () -> org.jetbrains.exposed.v1.jdbc.Query): List<Int> =
        query().withDistinct().orderBy(column to SortOrder.ASC).limit(MAX_REFERENCED_VERSIONS + 1)
            .map { it[column] }
            .also(::requireBounded)

    private fun distinctNullableInts(column: org.jetbrains.exposed.v1.core.Column<Int?>, query: () -> org.jetbrains.exposed.v1.jdbc.Query): List<Int> =
        query().withDistinct().orderBy(column to SortOrder.ASC).limit(MAX_REFERENCED_VERSIONS + 1)
            .mapNotNull { it[column] }
            .also(::requireBounded)

    private fun distinctStrings(column: org.jetbrains.exposed.v1.core.Column<String?>, query: () -> org.jetbrains.exposed.v1.jdbc.Query): List<String> =
        query().withDistinct().orderBy(column to SortOrder.ASC).limit(MAX_REFERENCED_VERSIONS + 1)
            .mapNotNull { it[column] }
            .also(::requireBounded)

    private fun requireBounded(versions: List<*>): List<*> = versions.also {
        if (it.size > MAX_REFERENCED_VERSIONS) throw VoucherKeyMaterialUnavailableException()
    }

    private inline fun <T> withExposedTransaction(crossinline block: () -> T): T {
        val database = Database.connect(dataSource)
        return try {
            transaction(database) { block() }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }
}

/** migration과 live referenced-key verification이 완료된 뒤에만 readiness를 엽니다. */
internal class VoucherPoolStartupInitializer(
    private val migration: VoucherPoolMigrationRunner,
    private val keyPreflight: VoucherPoolReferencedKeyPreflight,
    private val health: VoucherPoolHealthState,
): SmartInitializingSingleton {

    companion object: KLogging()

    override fun afterSingletonsInstantiated() {
        try {
            migration.migrate()
            health.recover(VoucherPoolHealthComponent.MIGRATION)
        } catch (failure: RuntimeException) {
            health.fail(
                VoucherPoolHealthComponent.MIGRATION,
                VoucherPoolHealthReason.MIGRATION_UNAVAILABLE
            )
            throw failure
        }
        try {
            keyPreflight.verify()
            health.recover(VoucherPoolHealthComponent.REFERENCED_KEYS)
        } catch (failure: RuntimeException) {
            health.fail(
                VoucherPoolHealthComponent.REFERENCED_KEYS,
                VoucherPoolHealthReason.REFERENCED_KEY_UNAVAILABLE,
            )
            throw failure
        }
        log.info { "voucher_pool_startup_authority_ready migration=001 referencedKeys=verified" }
    }
}
