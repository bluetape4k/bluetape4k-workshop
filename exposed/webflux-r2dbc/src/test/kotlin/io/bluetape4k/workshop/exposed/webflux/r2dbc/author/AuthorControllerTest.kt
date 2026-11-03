package io.bluetape4k.workshop.exposed.webflux.r2dbc.author

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.webflux.r2dbc.AbstractWebfluxR2dbcTest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.AuthorDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.BookCursorPageResponse
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.CreateAuthorRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.CreateBookRequest
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.expectBodyList

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthorControllerTest: AbstractWebfluxR2dbcTest() {

    companion object: KLoggingChannel()

    // findCursorPage endpoint 계약을 HTTP 응답으로 고정합니다.

    @Test
    fun `create and retrieve author`() {
        val req = CreateAuthorRequest(
            firstName = faker.name().firstName(),
            lastName = faker.name().lastName(),
            email = faker.internet().emailAddress(),
        )

        val created = webTestClient.post()
            .uri("/api/authors")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isCreated
            .expectBody<AuthorDTO>()
            .returnResult().responseBody.shouldNotBeNull()

        log.debug { "created=$created" }
        created.id shouldBeGreaterThan 0L
        created.firstName shouldBeEqualTo req.firstName
        created.email shouldBeEqualTo req.email

        webTestClient.get()
            .uri("/api/authors/${created.id}")
            .exchange()
            .expectStatus().isOk
            .expectBody<AuthorDTO>()
            .isEqualTo(created)
    }

    @Test
    fun `list authors returns non-empty`() {
        // 먼저 하나를 생성한다.
        val req = CreateAuthorRequest(
            firstName = faker.name().firstName(),
            lastName = faker.name().lastName(),
            email = faker.internet().emailAddress(),
        )
        webTestClient.post().uri("/api/authors")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isCreated

        val authors = webTestClient.get().uri("/api/authors")
            .exchange()
            .expectStatus().isOk
            .expectBodyList<AuthorDTO>()
            .returnResult().responseBody.shouldNotBeNull()

        authors.shouldNotBeEmpty()
            .onEach { log.debug { "author=$it" } }
    }

    @Test
    fun `cursor books returns bounded pages and advances by next cursor`() {
        val authorRequest = CreateAuthorRequest(
            firstName = faker.name().firstName(),
            lastName = faker.name().lastName(),
            email = faker.internet().emailAddress(),
        )
        val author = webTestClient.post()
            .uri("/api/authors")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(authorRequest)
            .exchange()
            .expectStatus().isCreated
            .expectBody(AuthorDTO::class.java)
            .returnResult().responseBody.shouldNotBeNull()
        log.debug { "author=$author" }

        repeat(5) { index ->
            webTestClient.post()
                .uri("/api/books")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    CreateBookRequest(
                        title = "Cursor book $index",
                        publishDate = "2026-07-05",
                        authorId = author.id,
                    )
                )
                .exchange()
                .expectStatus().isCreated
        }

        val first = webTestClient.get()
            .uri("/api/books/cursor?pageSize=2")
            .exchange()
            .expectStatus().isOk
            .expectBody<BookCursorPageResponse>()
            .returnResult().responseBody.shouldNotBeNull()

        log.debug { "first=$first" }
        first.content shouldHaveSize 2
        first.hasNext.shouldBeTrue()
        val cursor = first.nextCursor.shouldNotBeNull()

        val second = webTestClient.get()
            .uri("/api/books/cursor?pageSize=2&cursor=$cursor")
            .exchange()
            .expectStatus().isOk
            .expectBody<BookCursorPageResponse>()
            .returnResult().responseBody.shouldNotBeNull()

        log.debug { "second=$second" }
        second.content shouldHaveSize 2
        second.content.map(BookDTO::id).intersect(first.content.map(BookDTO::id).toSet()) shouldBeEqualTo emptySet()
    }

    @Test
    fun `cursor books rejects page size outside upstream guard`() {
        webTestClient.get()
            .uri("/api/books/cursor?pageSize=0")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `get non-existent author returns 404`() {
        webTestClient.get().uri("/api/authors/99999999")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `create author with blank email returns 400`() {
        val req = CreateAuthorRequest(
            firstName = faker.name().firstName(),
            lastName = faker.name().lastName(),
            email = " ",
        )
        webTestClient.post().uri("/api/authors")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `create book with nonexistent author returns 404`() {
        val req = CreateBookRequest(
            title = "Unknown Author Book",
            publishDate = "2026-07-05",
            authorId = 999_999L,
        )
        webTestClient.post().uri("/api/books")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `update author`() {
        val req = CreateAuthorRequest(
            firstName = faker.name().firstName(),
            lastName = faker.name().lastName(),
            email = faker.internet().emailAddress(),
        )
        val created = webTestClient.post().uri("/api/authors")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isCreated
            .expectBody<AuthorDTO>()
            .returnResult().responseBody.shouldNotBeNull()
        log.debug { "created=$created" }


        val updateReq = req.copy(firstName = "UpdatedName")
        val updated = webTestClient.put().uri("/api/authors/${created.id}")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(updateReq)
            .exchange()
            .expectStatus().isOk
            .expectBody<AuthorDTO>()
            .returnResult().responseBody.shouldNotBeNull()

        log.debug { "updated=$updated" }
        updated.firstName shouldBeEqualTo "UpdatedName"
    }

    @Test
    fun `delete author`() {
        val req = CreateAuthorRequest(
            firstName = faker.name().firstName(),
            lastName = faker.name().lastName(),
            email = faker.internet().emailAddress(),
        )
        val created = webTestClient.post().uri("/api/authors")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isCreated
            .expectBody<AuthorDTO>()
            .returnResult().responseBody.shouldNotBeNull()

        webTestClient.delete().uri("/api/authors/${created.id}")
            .exchange()
            .expectStatus().isNoContent

        webTestClient.get().uri("/api/authors/${created.id}")
            .exchange()
            .expectStatus().isNotFound
    }
}
