package io.bluetape4k.workshop.exposed.sql

import MigrationUtils
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction

/**
 * [SchemaUtils.createMissingTablesAndColumns] 를 대체하기 위한 함수입니다.
 */
fun Transaction.execCreateMissingTablesAndColumns(vararg tables: Table) {
    runCatching {
        val existingTables = SchemaUtils.listTables()
        val missingTables = tables.filterNot { existingTables.contains(it.tableName) }
        SchemaUtils.create(*missingTables.toTypedArray())
    }
    runCatching {
        MigrationUtils.statementsRequiredForDatabaseMigration(*tables).apply {
            if (isNotEmpty()) {
                exec(joinToString(";"))
            }
        }
    }

    SchemaUtils.addMissingColumnsStatements(*tables).apply {
        if (isNotEmpty()) {
            exec(joinToString(";"))
        }
    }
}
