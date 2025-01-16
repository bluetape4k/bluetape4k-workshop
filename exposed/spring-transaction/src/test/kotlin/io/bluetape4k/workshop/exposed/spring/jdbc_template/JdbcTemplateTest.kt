package io.bluetape4k.workshop.exposed.spring.jdbc_template

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest


@SpringBootTest(
    classes = [JdbcTemplateApplication::class],
    properties = [
        "spring.datasource.url=jdbc:h2:mem:testdb",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.exposed.generate-ddl=true"
    ]
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class JdbcTemplateTest {

    companion object: KLogging() {
        private const val REPEATED_SIZE = 5
    }

    @BeforeAll
    fun beforeAll() {
        transaction {
            SchemaUtils.create(AuthorTable, BookTable)
        }
    }

    @Autowired
    private val bookService: BookService = uninitialized()

    @Order(1)
    @DisplayName("Without spring transaction")
    @RepeatedTest(REPEATED_SIZE)
    fun `without spring transaction`() {
        bookService.testWithoutSpringTransaction()
    }

    @Order(2)
    @DisplayName("With spring transaction")
    @RepeatedTest(REPEATED_SIZE)
    fun `with spring transaction`() {
        bookService.testWithSpringTransaction()
    }

    @Order(3)
    @DisplayName("With exposed transaction")
    @RepeatedTest(REPEATED_SIZE)
    fun `with exposed transaction`() {
        bookService.testWithExposedTransaction()
    }

    @Order(4)
    @DisplayName("With spring and exposed transactions")
    @RepeatedTest(REPEATED_SIZE)
    fun `with spring and exposed transactions`() {
        bookService.testWithSpringAndExposedTransactions()
    }
}
