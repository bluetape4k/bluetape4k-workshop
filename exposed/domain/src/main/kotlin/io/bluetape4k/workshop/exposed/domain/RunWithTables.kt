package io.bluetape4k.workshop.exposed.domain

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction


fun runWithTables(
    vararg tables: Table,
    block: () -> Unit,
) {
    transaction {
        addLogger(StdOutSqlLogger)

        runCatching { SchemaUtils.createMissingTablesAndColumns(*tables) }

        try {
            block()
        } finally {
            SchemaUtils.drop(*tables)
        }
    }
}

suspend fun runSuspendWithTables(
    vararg tables: Table,
    block: suspend Transaction.() -> Unit,
) {
    newSuspendedTransaction(Dispatchers.IO) {
        addLogger(StdOutSqlLogger)

        runCatching { SchemaUtils.createMissingTablesAndColumns(*tables) }

        try {
            block()
        } finally {
            SchemaUtils.drop(*tables)
        }
    }
}
