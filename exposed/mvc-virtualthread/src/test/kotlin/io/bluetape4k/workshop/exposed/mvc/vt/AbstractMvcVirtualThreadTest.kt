package io.bluetape4k.workshop.exposed.mvc.vt

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.mvc.vt.author.schema.AuthorTable
import io.bluetape4k.workshop.exposed.mvc.vt.author.schema.BookTable
import io.bluetape4k.workshop.exposed.mvc.vt.order.schema.OrderLineTable
import io.bluetape4k.workshop.exposed.mvc.vt.order.schema.OrderTable
import io.bluetape4k.workshop.exposed.mvc.vt.order.schema.ProductTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.math.BigDecimal

@ActiveProfiles("postgres")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractMvcVirtualThreadTest {

    companion object : KLogging() {
        private val testDB = TestDB.POSTGRESQL

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { testDB.connection() }
            registry.add("spring.datasource.driver-class-name") { testDB.driver }
            registry.add("spring.datasource.username") { testDB.user }
            registry.add("spring.datasource.password") { testDB.pass }
        }

        val faker = Fakers.faker
    }

    @LocalServerPort
    protected val port: Int = 0

    protected val webTestClient: WebTestClient by lazy {
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    @Autowired
    private lateinit var db: Database

    @BeforeEach
    fun truncateAndReseed() {
        transaction(db) {
            // children first
            OrderLineTable.deleteAll()
            OrderTable.deleteAll()
            BookTable.deleteAll()
            ProductTable.deleteAll()
            AuthorTable.deleteAll()
        }
        transaction(db) {
            seedProducts()
            seedAuthors()
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
        val a1 = AuthorTable.insert {
            it[AuthorTable.firstName] = "Joshua"
            it[AuthorTable.lastName] = "Bloch"
            it[AuthorTable.email] = "joshua.bloch@example.com"
        }[AuthorTable.id]

        val a2 = AuthorTable.insert {
            it[AuthorTable.firstName] = "Martin"
            it[AuthorTable.lastName] = "Odersky"
            it[AuthorTable.email] = "martin.odersky@example.com"
        }[AuthorTable.id]

        BookTable.insert {
            it[BookTable.title] = "Effective Java"
            it[BookTable.publishDate] = "2018-01-01"
            it[BookTable.authorId] = a1
        }
        BookTable.insert {
            it[BookTable.title] = "Programming in Scala"
            it[BookTable.publishDate] = "2021-01-01"
            it[BookTable.authorId] = a2
        }
    }

    /**
     * Returns the ID of the first product by querying the products endpoint.
     * Use this to avoid hardcoding IDs that may drift after truncate+reseed.
     */
    protected fun firstProductId(): Long {
        val products = webTestClient.get()
            .uri("/api/v1/products")
            .exchange()
            .expectStatus().isOk
            .expectBodyList(Map::class.java)
            .returnResult()
            .responseBody!!
        return (products.first()["id"] as Number).toLong()
    }

    protected fun secondProductId(): Long {
        val products = webTestClient.get()
            .uri("/api/v1/products")
            .exchange()
            .expectStatus().isOk
            .expectBodyList(Map::class.java)
            .returnResult()
            .responseBody!!
        return (products[1]["id"] as Number).toLong()
    }

    protected fun firstAuthorId(): Long {
        val authors = webTestClient.get()
            .uri("/api/v1/authors")
            .exchange()
            .expectStatus().isOk
            .expectBodyList(Map::class.java)
            .returnResult()
            .responseBody!!
        return (authors.first()["id"] as Number).toLong()
    }
}
