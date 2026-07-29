package io.bluetape4k.workshop.exposed.mvc.jdbc.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.schema.AuthorTable
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.schema.BookTable
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.schema.OrderLineTable
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.schema.OrderTable
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.schema.ProductTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Component
class DatabaseInitializer : ApplicationRunner {

    companion object : KLogging()

    @Transactional
    override fun run(args: ApplicationArguments) {
        try {
            log.info { "Initializing database schema..." }
            SchemaUtils.create(AuthorTable, BookTable, ProductTable, OrderTable, OrderLineTable)

            if (ProductTable.selectAll().count() == 0L) {
                log.info { "Seeding initial data..." }
                seedProducts()
                seedAuthors()
            } else {
                log.info { "Database already seeded. Skipping." }
            }
        } catch (e: Exception) {
            throw IllegalStateException("DatabaseInitializer failed", e)
        }
    }

    private fun seedProducts() {
        listOf(
            Triple("Laptop", BigDecimal("999.99"), 50),
            Triple("Keyboard", BigDecimal("49.99"), 200),
            Triple("Mouse", BigDecimal("29.99"), 300),
            Triple("Monitor", BigDecimal("299.99"), 100),
            Triple("Headset", BigDecimal("79.99"), 150),
        ).forEach { (name, price, stock) ->
            ProductTable.insert {
                it[ProductTable.name] = name
                it[ProductTable.price] = price
                it[ProductTable.stock] = stock
            }
        }
    }

    private fun seedAuthors() {
        // AuthorTable은 AuditableLongIdTable을 상속하므로 insertAndGetId로 EntityID<Long>을 얻는다.
        val authorId1 = AuthorTable.insertAndGetId {
            it[firstName] = "Joshua"
            it[lastName] = "Bloch"
            it[email] = "joshua.bloch@example.com"
        }

        val authorId2 = AuthorTable.insertAndGetId {
            it[firstName] = "Martin"
            it[lastName] = "Odersky"
            it[email] = "martin.odersky@example.com"
        }

        val authorId3 = AuthorTable.insertAndGetId {
            it[firstName] = "Venkat"
            it[lastName] = "Subramaniam"
            it[email] = "venkat@example.com"
        }

        // BookTable.authorId는 reference("author_id", AuthorTable): Column<EntityID<Long>> 이다.
        // 여기서는 EntityID<Long>을 직접 넘기며 수동 EntityID wrapping은 필요하지 않다.
        listOf(
            Triple("Effective Java", "2018-01-01", authorId1),
            Triple("Programming in Scala", "2021-01-01", authorId2),
            Triple("Functional Programming in Java", "2023-01-01", authorId3),
            Triple("Atomic Kotlin", "2021-01-01", authorId3),
            Triple("The Joy of Kotlin", "2019-01-01", authorId3),
        ).forEach { (title, date, aId) ->
            BookTable.insert {
                it[BookTable.title] = title
                it[publishDate] = date
                it[authorId] = aId
            }
        }
    }
}
