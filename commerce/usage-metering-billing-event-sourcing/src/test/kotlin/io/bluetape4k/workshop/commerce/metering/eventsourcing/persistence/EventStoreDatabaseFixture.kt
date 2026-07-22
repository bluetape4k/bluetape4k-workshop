package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.postgresql.ds.PGSimpleDataSource

internal class EventStoreDatabaseFixture : AutoCloseable {
    val executor: MeteringEventsJdbcExecutor

    init {
        val dataSource = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username ?: PostgreSQLServer.USERNAME
            password = postgres.password ?: PostgreSQLServer.PASSWORD
        }
        executor = MeteringEventsJdbcExecutor(dataSource)
        reset()
    }

    @Suppress("DEPRECATION") // Disposable Testcontainers schema; production never creates schema automatically.
    fun reset() {
        executor.transaction {
            SchemaUtils.drop(*METERING_EVENT_TABLES.reversedArray())
            SchemaUtils.createMissingTablesAndColumns(*METERING_EVENT_TABLES)
        }
    }

    override fun close() {
        executor.transaction { SchemaUtils.drop(*METERING_EVENT_TABLES.reversedArray()) }
        executor.close()
    }

    private companion object {
        val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }
    }
}
