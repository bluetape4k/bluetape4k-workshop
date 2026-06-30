package io.bluetape4k.workshop.ktor.exposedrest

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.ktor.core.ApiErrorResponse
import io.bluetape4k.ktor.core.HealthResponse
import io.bluetape4k.ktor.testing.ExpectedApiError
import io.bluetape4k.ktor.testing.bluetape4kJsonClient
import io.bluetape4k.ktor.testing.decodeJsonBody
import io.bluetape4k.ktor.testing.shouldHaveApiError
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KtorExposedRestApplicationTest {

    private val postgres = PostgreSQLServer.Launcher.postgres

    @Test
    fun `postgres backed routes create list update and delete books`() = testApplication {
        val resources = postgresResources("crud")

        application {
            installKtorExposedRest(resources)
        }

        val http = bluetape4kJsonClient()
        val created = http.post("/api/books") {
            contentType(ContentType.Application.Json)
            setBody(BookRequest(title = "Ktor with Exposed", author = "Blue Tape", isbn = "978-0-001"))
        }.shouldHaveStatus(HttpStatusCode.Created)
            .decodeJsonBody<BookResponse>()

        created.title shouldBeEqualTo "Ktor with Exposed"
        created.author shouldBeEqualTo "Blue Tape"

        val listed = http.get("/api/books")
            .shouldHaveStatus(HttpStatusCode.OK)
            .decodeJsonBody<List<BookResponse>>()
        listed.single().id shouldBeEqualTo created.id

        val read = http.get("/api/books/${created.id}")
            .shouldHaveStatus(HttpStatusCode.OK)
            .decodeJsonBody<BookResponse>()
        read shouldBeEqualTo created

        val updated = http.put("/api/books/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody(BookRequest(title = "Ktor Transactions", author = "Blue Tape", isbn = "978-0-002"))
        }.shouldHaveStatus(HttpStatusCode.OK)
            .decodeJsonBody<BookResponse>()
        updated.title shouldBeEqualTo "Ktor Transactions"
        updated.isbn shouldBeEqualTo "978-0-002"

        http.delete("/api/books/${created.id}")
            .shouldHaveStatus(HttpStatusCode.NoContent)

        val empty = http.get("/api/books")
            .shouldHaveStatus(HttpStatusCode.OK)
            .decodeJsonBody<List<BookResponse>>()
        empty.size shouldBeEqualTo 0
    }

    @Test
    fun `rollback route leaves PostgreSQL state unchanged and returns safe transaction error`() = testApplication {
        val resources = postgresResources("rollback")

        application {
            installKtorExposedRest(resources)
        }

        val http = bluetape4kJsonClient()
        val response = http.post("/api/books/rollback") {
            contentType(ContentType.Application.Json)
            setBody(BookRequest(title = "Rollback Candidate", author = "Blue Tape", isbn = "978-0-010"))
        }

        response.shouldHaveApiError(
            ExpectedApiError(
                status = HttpStatusCode.InternalServerError,
                error = "EXPOSED_TRANSACTION_FAILED",
                message = "Exposed transaction failed",
                path = "/api/books/rollback",
            )
        )
        response.bodyAsText() shouldNotContain "Rollback Candidate"
        response.bodyAsText() shouldNotContain postgres.jdbcUrl
        response.bodyAsText() shouldNotContain requireNotNull(postgres.password)

        val books = http.get("/api/books")
            .shouldHaveStatus(HttpStatusCode.OK)
            .decodeJsonBody<List<BookResponse>>()
        books.size shouldBeEqualTo 0
    }

    @Test
    fun `direct SQL failures are sanitized by exposed status pages`() = testApplication {
        val resources = postgresResources("sql-failure")

        application {
            installKtorExposedRest(resources)
        }

        val http = bluetape4kJsonClient()
        val response = http.get("/api/failures/sql")
        response.shouldHaveApiError(
            ExpectedApiError(
                status = HttpStatusCode.ServiceUnavailable,
                error = "EXPOSED_DATABASE_UNAVAILABLE",
                message = "Exposed database operation failed",
                path = "/api/failures/sql",
            )
        )

        val body = response.bodyAsText()
        body shouldNotContain postgres.jdbcUrl
        body shouldNotContain requireNotNull(postgres.username)
        body shouldNotContain requireNotNull(postgres.password)
        body shouldNotContain "select * from secret_books"
    }

    @Test
    fun `exposed readiness reports PostgreSQL availability`() = testApplication {
        val resources = postgresResources("readiness")

        application {
            installKtorExposedRest(resources)
        }

        val http = bluetape4kJsonClient()
        val health = http.get("/healthz/exposed")
            .shouldHaveStatus(HttpStatusCode.OK)
            .decodeJsonBody<HealthResponse>()
        health shouldBeEqualTo HealthResponse.up(mapOf("exposed" to HealthResponse.UP))

        val readiness = http.get("/readyz/exposed")
            .shouldHaveStatus(HttpStatusCode.OK)
            .decodeJsonBody<HealthResponse>()
        readiness shouldBeEqualTo HealthResponse.up(mapOf("jdbc" to HealthResponse.UP))
    }

    @Test
    fun `cancellation is propagated and not rendered as a database error`() = testApplication {
        val resources = postgresResources("cancelled")

        application {
            installKtorExposedRest(resources)
        }

        val http = bluetape4kJsonClient()
        val response = http.get("/api/failures/cancelled")

        response.shouldHaveStatus(HttpStatusCode.InternalServerError)
        val body = response.bodyAsText()
        body shouldNotContain "EXPOSED_TRANSACTION_FAILED"
        body shouldNotContain "EXPOSED_DATABASE_UNAVAILABLE"
        body shouldNotContain postgres.jdbcUrl
    }

    @Test
    fun `invalid requests use generic API error mapping`() = testApplication {
        val resources = postgresResources("validation")

        application {
            installKtorExposedRest(resources)
        }

        val http = bluetape4kJsonClient()
        val response = http.post("/api/books") {
            contentType(ContentType.Application.Json)
            setBody(BookRequest(title = " ", author = "Blue Tape", isbn = "978-0-099"))
        }

        response.shouldHaveStatus(HttpStatusCode.BadRequest)
        val error = response.decodeJsonBody<ApiErrorResponse>()
        error.error shouldBeEqualTo "bad_request"
        error.message shouldContain "title"
    }

    private fun postgresResources(name: String): KtorExposedRestResources =
        KtorExposedRestResources.create(
            jdbcUrl = postgres.jdbcUrl,
            username = requireNotNull(postgres.username),
            password = requireNotNull(postgres.password),
            driverClassName = postgres.driverClassName,
            poolName = "ktor-exposed-rest-$name",
        )
}
