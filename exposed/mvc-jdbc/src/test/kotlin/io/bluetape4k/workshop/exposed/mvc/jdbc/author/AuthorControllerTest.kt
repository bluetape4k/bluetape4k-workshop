package io.bluetape4k.workshop.exposed.mvc.jdbc.author

import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.exposed.mvc.jdbc.AbstractMvcJdbcTest
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.AuthorDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.BookCursorPageResponse
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.CreateAuthorRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.CreateBookRequest
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class AuthorControllerTest : AbstractMvcJdbcTest() {

    // findCursorPage endpoint 계약을 HTTP 응답으로 고정합니다.

    @Test
    fun `GET authors returns list`() {
        val result = webTestClient.get()
            .uri("/api/v1/authors")
            .exchange()
            .expectStatus().isOk
            .expectBodyList(AuthorDTO::class.java)
            .returnResult()
        result.responseBody.shouldNotBeNull()
    }

    @Test
    fun `POST author creates author`() {
        val req = CreateAuthorRequest(
            firstName = faker.name().firstName(),
            lastName = faker.name().lastName(),
            email = faker.internet().emailAddress()
        )
        val result = webTestClient.post()
            .uri("/api/v1/authors")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isCreated
            .expectBody(AuthorDTO::class.java)
            .returnResult()
        result.responseBody.shouldNotBeNull().id shouldBeGreaterOrEqualTo 1L
    }

    @Test
    fun `POST author with blank email returns bad request`() {
        val req = CreateAuthorRequest(
            firstName = faker.name().firstName(),
            lastName = faker.name().lastName(),
            email = " ",
        )

        webTestClient.post()
            .uri("/api/v1/authors")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `GET author books returns books`() {
        val result = webTestClient.get()
            .uri("/api/v1/authors/1/books")
            .exchange()
            .expectStatus().isOk
            .expectBodyList(BookDTO::class.java)
            .returnResult()
        result.responseBody.shouldNotBeNull()
    }

    @Test
    fun `GET books cursor returns bounded pages and advances by next cursor`() {
        val first = webTestClient.get()
            .uri("/api/v1/books/cursor?pageSize=2")
            .exchange()
            .expectStatus().isOk
            .expectBody(BookCursorPageResponse::class.java)
            .returnResult().responseBody.shouldNotBeNull()

        first.content shouldHaveSize 2
        first.hasNext.shouldBeTrue()
        val cursor = first.nextCursor.shouldNotBeNull()

        val second = webTestClient.get()
            .uri("/api/v1/books/cursor?pageSize=2&cursor=$cursor")
            .exchange()
            .expectStatus().isOk
            .expectBody(BookCursorPageResponse::class.java)
            .returnResult().responseBody.shouldNotBeNull()

        second.content shouldHaveSize 2
        second.content.map(BookDTO::id).intersect(first.content.map(BookDTO::id).toSet()) shouldBeEqualTo emptySet()
    }

    @Test
    fun `GET books cursor rejects page size outside upstream guard`() {
        webTestClient.get()
            .uri("/api/v1/books/cursor?pageSize=0")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `POST book with nonexistent author returns not found`() {
        val req = CreateBookRequest(
            title = "Missing Author ${faker.number().digits(6)}",
            publishDate = "2026-07-05",
            authorId = 999_999L,
        )

        webTestClient.post()
            .uri("/api/v1/books")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isNotFound
    }
}
