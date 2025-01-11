package io.bluetape4k.workshop.exposed.domain

import io.bluetape4k.logging.KotlinLogging
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.inTopLevelTransaction
import org.jetbrains.exposed.sql.transactions.transactionManager

private val log = KotlinLogging.logger {}

fun withTables(
    dialect: TestDB,
    vararg tables: Table,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: Transaction.(TestDB) -> Unit,
) {
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

suspend fun withSuspendedTables(
    dialect: TestDB,
    vararg tables: Table,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: suspend Transaction.(TestDB) -> Unit,
) {
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
