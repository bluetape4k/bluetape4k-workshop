package io.bluetape4k.workshop.exposed.domain

import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.Schema
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction

fun withSchemas(
    vararg schemas: Schema,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: Transaction.() -> Unit,
) =
    withSchemas(emptySet(), *schemas, configure = configure, statement = statement)

fun withSchemas(
    excludeSettings: Collection<TestDB>,
    vararg schemas: Schema,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: Transaction.() -> Unit,
) {
    val settings = TestDB.enabledDialects() - excludeSettings

    settings.forEach { dialect ->
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
}
