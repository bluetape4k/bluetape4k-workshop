package io.bluetape4k.workshop.exposed.webflux.r2dbc.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.schema.AuthorTable
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.schema.BookTable
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.schema.OrderLineTable
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.schema.OrderTable
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.schema.ProductTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component

@Component
class DatabaseInitializer(
    @Value("\${spring.r2dbc.url}") private val r2dbcUrl: String,
    @Value("\${spring.r2dbc.username}") private val username: String,
    @Value("\${spring.r2dbc.password}") private val password: String,
) : ApplicationListener<ApplicationReadyEvent> {

    companion object : KLoggingChannel()

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        log.info { "Initializing database schema via JDBC ..." }

        val jdbcUrl = r2dbcUrl
            .replace("r2dbc:pool:postgresql", "jdbc:postgresql")
            .replace("r2dbc:postgresql", "jdbc:postgresql")

        val hikariConfig = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = this@DatabaseInitializer.username
            this.password = this@DatabaseInitializer.password
            this.driverClassName = "org.postgresql.Driver"
            this.maximumPoolSize = 2
            this.connectionTimeout = 10_000
        }

        val dataSource = HikariDataSource(hikariConfig)
        try {
            val db = Database.connect(dataSource)
            transaction(db) {
                SchemaUtils.createMissingTablesAndColumns(
                    AuthorTable,
                    BookTable,
                    ProductTable,
                    OrderTable,
                    OrderLineTable,
                )
            }
            log.info { "Database schema initialized." }
        } finally {
            dataSource.close()
        }
    }
}
