package io.bluetape4k.workshop.ktor.routes

import io.bluetape4k.workshop.ktor.AbstractKtorTest
import io.bluetape4k.workshop.ktor.domain.Book
import io.bluetape4k.workshop.ktor.domain.DomainError
import io.bluetape4k.workshop.ktor.module
import io.bluetape4k.workshop.ktor.repository.BookRepository
import io.bluetape4k.workshop.ktor.repository.InMemoryBookRepository
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test

class BookRoutesTest : AbstractKtorTest() {

    @Test
    fun `GET books returns empty list when repository is empty`() = testApplication {
        application { module() }

        val response = client.get("/books")

        response.status shouldBeEqualTo HttpStatusCode.OK
        response.bodyAsText() shouldBeEqualTo "[]"
    }

    @Test
    fun `GET books returns all seeded books`() = testApplication {
        val repository = InMemoryBookRepository()
        val books = createBooks(3)
        books.forEach { repository.save(it) }
        application { module(repository = repository) }

        val response = client.get("/books")

        response.status shouldBeEqualTo HttpStatusCode.OK
        val body = response.bodyAsText()
        books.forEach { body shouldContain it.id }
    }

    @Test
    fun `GET books by id returns book when it exists`() = testApplication {
        val repository = InMemoryBookRepository()
        val book = createBook(id = "exists-1")
        repository.save(book)
        application { module(repository = repository) }

        val response = client.get("/books/${book.id}")

        response.status shouldBeEqualTo HttpStatusCode.OK
        response.bodyAsText() shouldContain book.id
    }

    @Test
    fun `GET books by id returns 404 with type NotFound when missing`() = testApplication {
        application { module() }

        val response = client.get("/books/no-such-id")

        response.status shouldBeEqualTo HttpStatusCode.NotFound
        response.bodyAsText() shouldContain "NotFound"
    }

    @Test
    fun `POST books creates book and returns 201`() = testApplication {
        application { module() }

        val response = client.post("/books") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"new-1","title":"Clean Code","author":"Martin","year":2008}""")
        }

        response.status shouldBeEqualTo HttpStatusCode.Created
        response.bodyAsText() shouldContain "new-1"
    }

    @Test
    fun `POST books returns 409 Conflict for duplicate id`() = testApplication {
        val repository = InMemoryBookRepository()
        repository.save(createBook(id = "dup-1"))
        application { module(repository = repository) }

        val response = client.post("/books") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"dup-1","title":"Any","author":"Author","year":2020}""")
        }

        response.status shouldBeEqualTo HttpStatusCode.Conflict
        response.bodyAsText() shouldContain "Conflict"
    }

    @Test
    fun `POST books returns 400 when title is blank`() = testApplication {
        application { module() }

        val response = client.post("/books") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"bad-1","title":"","author":"Author","year":2020}""")
        }

        response.status shouldBeEqualTo HttpStatusCode.BadRequest
        response.bodyAsText() shouldContain "BadRequest"
    }

    @Test
    fun `POST books returns 400 when id is blank`() = testApplication {
        application { module() }

        val response = client.post("/books") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"","title":"Title","author":"Author","year":2020}""")
        }

        response.status shouldBeEqualTo HttpStatusCode.BadRequest
        response.bodyAsText() shouldContain "BadRequest"
    }

    @Test
    fun `POST books returns 400 when year is out of range`() = testApplication {
        application { module() }

        val response = client.post("/books") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"bad-year","title":"Title","author":"Author","year":0}""")
        }

        response.status shouldBeEqualTo HttpStatusCode.BadRequest
        response.bodyAsText() shouldContain "BadRequest"
    }

    @Test
    fun `GET books export returns NDJSON with one line per book`() = testApplication {
        val repository = InMemoryBookRepository()
        val books = createBooks(3)
        books.forEach { repository.save(it) }
        application { module(repository = repository) }

        val response = client.get("/books/export")

        response.status shouldBeEqualTo HttpStatusCode.OK
        response.contentType().toString() shouldContain "x-ndjson"

        val lines = response.bodyAsText().trim().lines().filter { it.isNotBlank() }
        lines.size shouldBeEqualTo books.size
        books.forEach { book ->
            lines.any { it.contains(book.id) } shouldBeEqualTo true
        }
    }

    @Test
    fun `GET health succeeds even with SSE installed`() = testApplication {
        application { module() }

        val response = client.get("/health")

        response.status shouldBeEqualTo HttpStatusCode.OK
        response.bodyAsText() shouldBeEqualTo "OK"
    }

    @Test
    fun `unhandled exception returns 500 with type Internal and no stack trace`() = testApplication {
        val fakeRepository = object : BookRepository {
            override suspend fun findAll(): List<Book> = throw RuntimeException("boom")
            override suspend fun findById(id: String): Book? = null
            override suspend fun save(book: Book): Book = throw UnsupportedOperationException()
            override suspend fun update(id: String, book: Book): Book = throw UnsupportedOperationException()
            override suspend fun delete(id: String) = throw UnsupportedOperationException()
            override fun stream(): Flow<Book> = emptyFlow()
        }
        application { module(repository = fakeRepository) }

        val response = client.get("/books")

        response.status shouldBeEqualTo HttpStatusCode.InternalServerError
        val body = response.bodyAsText()
        body shouldContain "Internal"
        body shouldNotContain "RuntimeException"
        body shouldNotContain "at io."
    }
}
