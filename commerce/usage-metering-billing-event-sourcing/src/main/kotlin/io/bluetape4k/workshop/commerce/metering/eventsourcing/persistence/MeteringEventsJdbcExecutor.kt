package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

class MeteringEventsJdbcExecutor(dataSource: DataSource) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val database = Database.connect(
        datasource = dataSource,
        databaseConfig = DatabaseConfig { defaultMaxAttempts = 1 },
    )

    init {
        TransactionManager.defaultDatabase = database
    }

    fun <T> transaction(block: JdbcTransaction.() -> T): T {
        check(!closed.get()) { "metering_events_jdbc_executor_closed" }
        return transaction(database) { block() }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            TransactionManager.closeAndUnregister(database)
        }
    }
}
