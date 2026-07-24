package io.bluetape4k.workshop.commerce.voucher.eventsourced.support

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getHikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database

internal class EventSourcedPostgresTestDatabase private constructor(
    val dataSource: HikariDataSource,
    val database: Database,
) : AutoCloseable {
    override fun close() = dataSource.close()

    companion object {
        operator fun invoke(
            postgres: PostgreSQLServer,
            poolName: String,
            maximumPoolSize: Int = 4,
        ): EventSourcedPostgresTestDatabase {
            val dataSource =
                postgres.getHikariDataSource {
                    this.poolName = poolName
                    this.maximumPoolSize = maximumPoolSize
                    minimumIdle = 0
                }
            return EventSourcedPostgresTestDatabase(dataSource, Database.connect(dataSource))
        }
    }
}
