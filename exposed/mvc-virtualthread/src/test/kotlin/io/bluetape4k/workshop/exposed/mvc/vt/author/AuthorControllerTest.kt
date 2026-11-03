package io.bluetape4k.workshop.exposed.mvc.vt.author

import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.mvc.vt.AbstractMvcVirtualThreadTest
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.AuthorDTO
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.CreateAuthorRequest
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.CreateBookRequest
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.expectBodyList

class AuthorControllerTest: AbstractMvcVirtualThreadTest() {

    companion object: KLogging()

    @Test
    fun `GET authors returns list`() {
        val result = webTestClient.get()
            .uri("/api/v1/authors")
            .exchange()
            .expectStatus().isOk
            .expectBodyList<AuthorDTO>()
            .returnResult()

        result.responseBody.shouldNotBeNull()
            .onEach { log.debug { "author=$it" } }
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
            .expectBody<AuthorDTO>()
            .returnResult()

        val author = result.responseBody.shouldNotBeNull()
        log.debug { "author=$author" }
        author.id shouldBeGreaterOrEqualTo 1L
    }

    @Test
    fun `POST author with blank email returns 400`() {
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
    fun `POST book with nonexistent author returns 404`() {
        val req = CreateBookRequest(
            title = "Unknown Author Book",
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

    @Test
    fun `GET author books returns books`() {
        val authorId = firstAuthorId()
        val result = webTestClient.get()
            .uri("/api/v1/authors/$authorId/books")
            .exchange()
            .expectStatus().isOk
            .expectBodyList<BookDTO>()
            .returnResult()

        result.responseBody.shouldNotBeNull().onEach { log.debug { "book=$it" } }
    }
}
