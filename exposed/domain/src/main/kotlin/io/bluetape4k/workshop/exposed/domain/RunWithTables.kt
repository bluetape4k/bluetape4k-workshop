package io.bluetape4k.workshop.exposed.domain

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction


inline fun runWithTables(
    vararg tables: Table,
    crossinline block: () -> Unit,
) = transaction {
    SchemaUtils.createMissingTablesAndColumns(*tables)
    try {
        block()
    } finally {
        SchemaUtils.drop(*tables)
    }
}

suspend inline fun runSuspendWithTables(
    vararg tables: Table,
    crossinline block: suspend Transaction.() -> Unit,
) = newSuspendedTransaction(Dispatchers.IO) {
    SchemaUtils.createMissingTablesAndColumns(*tables)
    try {
        block()
    } finally {
        SchemaUtils.drop(*tables)
    }
}
