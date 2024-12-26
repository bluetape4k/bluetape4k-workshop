package io.bluetape4k.workshop.exposed.domain

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction

fun withTables(
    vararg tables: Table,
    statement: Transaction.(TestDB) -> Unit,
) = withTables(excludeSettings = emptySet(), tables = tables, statement = statement)

fun withTables(
    excludeSettings: Collection<TestDB>,
    vararg tables: Table,
    statement: Transaction.(TestDB) -> Unit,
) {
    val settings = TestDB.enabledDialects() - excludeSettings

    settings.forEach {
        withDb(it) { dialect ->
            SchemaUtils.create(*tables)
            try {
                statement(dialect)
                commit()
            } finally {
                SchemaUtils.drop(*tables)
            }
        }
    }
}

suspend fun withSuspendedTables(
    vararg tables: Table,
    statement: suspend Transaction.(TestDB) -> Unit,
) = withSuspendedTables(excludeSettings = emptySet(), tables = tables, statement = statement)

suspend fun withSuspendedTables(
    excludeSettings: Collection<TestDB>,
    vararg tables: Table,
    statement: suspend Transaction.(TestDB) -> Unit,
) {
    val settings = TestDB.enabledDialects() - excludeSettings

    settings.forEach {
        withSuspendedDb(it) { dialect ->
            SchemaUtils.create(*tables)
            try {
                statement(dialect)
                commit()
            } finally {
                SchemaUtils.drop(*tables)
            }
        }
    }
}
