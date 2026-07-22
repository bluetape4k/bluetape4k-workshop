package io.bluetape4k.workshop.commerce.metering.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.postgresql.ds.PGSimpleDataSource
import java.time.Instant
import java.util.UUID

internal data class MeteringSeed(
    val tenantId: String = "tenant-a",
    val meterCode: String = "api_calls",
    val currency: String = "USD",
    val meterId: UUID = Uuid.V7.nextId(),
    val calendarId: UUID = Uuid.V7.nextId(),
)

internal class MeteringDatabaseFixture : AutoCloseable {
    val executor: MeteringJdbcExecutor

    init {
        val dataSource =
            PGSimpleDataSource().apply {
                setURL(postgres.jdbcUrl)
                user = postgres.username ?: PostgreSQLServer.USERNAME
                password = postgres.password ?: PostgreSQLServer.PASSWORD
            }
        executor = MeteringJdbcExecutor(dataSource, foregroundPermits = 8)
        resetAndSeed()
    }

    @Suppress("DEPRECATION") // Test-only ephemeral schemas deliberately avoid production migration artifacts.
    fun resetAndSeed(seed: MeteringSeed = MeteringSeed()): MeteringSeed {
        executor.transaction {
            SchemaUtils.drop(*METERING_TABLES.reversedArray())
            SchemaUtils.createMissingTablesAndColumns(*METERING_TABLES)
            MeterEntity.new(seed.meterId) {
                tenantId = seed.tenantId
                meterCode = seed.meterCode
                unit = "request"
                description = "API calls accepted by the platform"
                active = true
                createdAt = Instant.parse("2026-07-01T00:00:00Z")
            }
            BillingCalendarEntity.new(seed.calendarId) {
                tenantId = seed.tenantId
                currency = seed.currency
                createdAt = Instant.parse("2026-07-01T00:00:00Z")
            }
        }
        return seed
    }

    override fun close() {
        executor.transaction {
            SchemaUtils.drop(*METERING_TABLES.reversedArray())
        }
        executor.close()
    }

    companion object {
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }
    }
}
