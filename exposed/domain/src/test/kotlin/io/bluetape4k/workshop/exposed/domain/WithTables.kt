package io.bluetape4k.workshop.exposed.domain

import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.inTopLevelTransaction
import org.jetbrains.exposed.sql.transactions.transactionManager

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

    settings.forEach { dialect ->
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
    }
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

    settings.forEach { dialect ->
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
    }
}
