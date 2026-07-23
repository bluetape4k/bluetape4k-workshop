package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventLog
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class EventSourcedStartupProbeIntegrationTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var probe: EventSourcedStartupProbe

    @BeforeAll
    fun connectPostgres() {
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = requireNotNull(postgres.username)
                    password = requireNotNull(postgres.password)
                    maximumPoolSize = 1
                },
            )
        database = Database.connect(dataSource)
        probe = EventSourcedOperationsConfiguration().eventSourcedStartupProbe(dataSource)
    }

    @BeforeEach
    fun clearSchema() {
        transaction(database) { SchemaUtils.drop(EventLog) }
    }

    @AfterEach
    fun dropSchema() {
        transaction(database) { SchemaUtils.drop(EventLog) }
    }

    @AfterAll
    fun closeDataSource() {
        dataSource.close()
    }

    @Test
    fun `probe rejects a missing event authority schema and accepts it after creation`() {
        assertFailsWith<IllegalStateException> { probe.verify() }

        transaction(database) { SchemaUtils.create(EventLog) }

        probe.verify()
    }
}
