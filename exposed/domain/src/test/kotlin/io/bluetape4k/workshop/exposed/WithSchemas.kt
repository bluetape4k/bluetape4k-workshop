package io.bluetape4k.workshop.exposed

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Schema
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils


fun withSchemas(
    dialect: TestDB,
    vararg schemas: Schema,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: JdbcTransaction.() -> Unit,
) {
    withDb(dialect, configure) {
        if (currentDialectTest.supportsCreateSchema) {
            SchemaUtils.createSchema(*schemas)
            try {
                statement()
                commit()     // Need commit to persist data before drop schemas
            } finally {
                SchemaUtils.dropSchema(*schemas, cascade = true)
                commit()
            }
        }
    }
}
