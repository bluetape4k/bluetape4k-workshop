package io.bluetape4k.workshop.exposed.mvc.vt.author

import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.exposed.mvc.vt.AbstractMvcVirtualThreadTest
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.AuthorDTO
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.CreateAuthorRequest
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.CreateBookRequest
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class AuthorControllerTest : AbstractMvcVirtualThreadTest() {

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
        val body = result.responseBody.shouldNotBeNull()
        body.id shouldBeGreaterOrEqualTo 1L
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
            .expectBodyList(BookDTO::class.java)
            .returnResult()
        result.responseBody.shouldNotBeNull()
    }
}
