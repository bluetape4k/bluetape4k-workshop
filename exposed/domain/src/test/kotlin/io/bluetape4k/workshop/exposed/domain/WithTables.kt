package io.bluetape4k.workshop.exposed.domain

import io.bluetape4k.junit5.utils.MultiException
import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.error
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.inTopLevelTransaction
import org.jetbrains.exposed.sql.transactions.transactionManager

private val log = KotlinLogging.logger {}

fun withTables(
    vararg tables: Table,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: Transaction.(TestDB) -> Unit,
) = withTables(
    excludeSettings = emptySet(),
    tables = tables,
    configure = configure,
    statement = statement
)

fun withTables(
    excludeSettings: Collection<TestDB>,
    vararg tables: Table,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: Transaction.(TestDB) -> Unit,
) {
    val settings = TestDB.enabledDialects() - excludeSettings.toSet()

    val me = MultiException()
    settings.forEach { dialect ->
        runCatching {
            withDb(dialect, configure) {
                try {
                    SchemaUtils.drop(*tables)
                } catch (_: Throwable) {
                }

                SchemaUtils.create(*tables)
                try {
                    statement(dialect)
                    commit()  // Need commit to persist data before drop tables
                } finally {
                    try {
                        SchemaUtils.drop(*tables)
                        commit()
                    } catch (_: Exception) {
                        val database = dialect.db!!
                        inTopLevelTransaction(database.transactionManager.defaultIsolationLevel, db = database) {
                            maxAttempts = 1
                            SchemaUtils.drop(*tables)
                        }
                    }
                }
            }
        }.onFailure {
            me.add(it)
            log.error(it) { "Failed to run withTables for $dialect" }
        }
    }
    me.throwIfNotEmpty()
}

suspend fun withSuspendedTables(
    vararg tables: Table,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: suspend Transaction.(TestDB) -> Unit,
) = withSuspendedTables(
    excludeSettings = emptySet(),
    tables = tables,
    configure = configure,
    statement = statement
)

suspend fun withSuspendedTables(
    excludeSettings: Collection<TestDB>,
    vararg tables: Table,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: suspend Transaction.(TestDB) -> Unit,
) {
    val settings = TestDB.enabledDialects() - excludeSettings

    val me = MultiException()

    settings.forEach { dialect ->
        runCatching {
            withSuspendedDb(dialect, configure) {
                try {
                    SchemaUtils.drop(*tables)
                } catch (_: Throwable) {
                }

                SchemaUtils.create(*tables)
                try {
                    statement(dialect)
                    commit()
                } finally {
                    try {
                        SchemaUtils.drop(*tables)
                        commit()
                    } catch (_: Exception) {
                        val database = dialect.db!!
                        inTopLevelTransaction(database.transactionManager.defaultIsolationLevel, db = database) {
                            maxAttempts = 1
                            SchemaUtils.drop(*tables)
                        }
                    }
                }
            }
        }.onFailure {
            me.add(it)
            log.error(it) { "Failed to run withSuspendedTables for $dialect" }
        }

        me.throwIfNotEmpty()
    }
}
