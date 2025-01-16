package io.bluetape4k.workshop.exposed.spring.jdbc_template

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionOperations

@Component
class BookService(
    @Qualifier("operations1") private val operations1: TransactionOperations,
    @Qualifier("operations2") private val operations2: TransactionOperations,
    private val jdbcTemplate: JdbcTemplate,
) {

    fun testWithSpringAndExposedTransactions() {
        testWithExposedTransaction()
        testWithSpringTransaction()
    }

    fun testWithSpringTransaction() {
        operations1.execute {
            val id = TimebasedUuid.Epoch.nextId().toString()
            val query = "INSERT INTO AUTHORS(id, description) values ('$id', '234234')"
            jdbcTemplate.execute(query)
        }
    }

    fun testWithExposedTransaction() {
        transaction {
            Book.new { description = "1234" }
        }
    }

    fun testWithoutSpringTransaction() {
        transaction {
            Book.new { description = "1234" }
        }
        operations2.execute {
            val id = TimebasedUuid.Epoch.nextId().toString()
            val query = "INSERT INTO AUTHORS(id, description) values ('$id', '234234')"
            jdbcTemplate.execute(query)
        }
    }
}
