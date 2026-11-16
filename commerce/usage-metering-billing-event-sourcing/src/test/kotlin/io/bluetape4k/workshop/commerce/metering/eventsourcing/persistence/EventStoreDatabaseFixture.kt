package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getHikariDataSource
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

internal class EventStoreDatabaseFixture: AutoCloseable {

    private companion object: KLogging() {
        val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }
    }

    val executor: MeteringEventsJdbcExecutor

    init {
        val dataSource = postgres.getHikariDataSource {}
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
        executor.transaction {
            SchemaUtils.drop(*METERING_EVENT_TABLES.reversedArray())
        }
        executor.close()
    }
}
