package io.bluetape4k.workshop.exposed.domain

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction

fun withTables(
    vararg tables: Table,
    block: Transaction.() -> Unit,
) = withTables(excludeSettings = emptySet(), tables = tables, block = block)

fun withTables(
    excludeSettings: Set<TestDB>,
    vararg tables: Table,
    block: Transaction.() -> Unit,
) {
    val settings = TestDB.enabledDialects() - excludeSettings

    settings.forEach {
        withDb(it) {
            SchemaUtils.create(*tables)
            try {
                block()
                commit()
            } finally {
                SchemaUtils.drop(*tables)
            }
        }
    }
}

suspend fun withSuspendedTables(
    vararg tables: Table,
    block: suspend Transaction.() -> Unit,
) = withSuspendedTables(excludeSettings = emptySet(), tables = tables, block = block)

suspend fun withSuspendedTables(
    excludeSettings: Set<TestDB>,
    vararg tables: Table,
    block: suspend Transaction.() -> Unit,
) {
    val settings = TestDB.enabledDialects() - excludeSettings

    settings.forEach {
        withSuspendedDb(it) {
            SchemaUtils.create(*tables)
            try {
                block()
                commit()
            } finally {
                SchemaUtils.drop(*tables)
            }
        }
    }
}
