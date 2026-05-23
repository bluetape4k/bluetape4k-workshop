package io.bluetape4k.workshop.exposed.webflux.r2dbc.author

import io.bluetape4k.workshop.exposed.webflux.r2dbc.AbstractWebfluxR2dbcTest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.AuthorDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.CreateAuthorRequest
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.hamcrest.Matchers

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthorControllerTest : AbstractWebfluxR2dbcTest() {

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
            .expectBody(AuthorDTO::class.java)
            .returnResult().responseBody!!

        assert(created.id > 0)
        assert(created.firstName == req.firstName)
        assert(created.email == req.email)

        webTestClient.get()
            .uri("/api/authors/${created.id}")
            .exchange()
            .expectStatus().isOk
            .expectBody(AuthorDTO::class.java)
            .isEqualTo(created)
    }

    @Test
    fun `list authors returns non-empty`() {
        // create one first
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
            .expectBodyList(AuthorDTO::class.java)
            .returnResult().responseBody!!
        assert(authors.isNotEmpty())
    }

    @Test
    fun `get non-existent author returns 404`() {
        webTestClient.get().uri("/api/authors/99999999")
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
            .expectBody(AuthorDTO::class.java)
            .returnResult().responseBody!!

        val updateReq = req.copy(firstName = "UpdatedName")
        val updated = webTestClient.put().uri("/api/authors/${created.id}")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(updateReq)
            .exchange()
            .expectStatus().isOk
            .expectBody(AuthorDTO::class.java)
            .returnResult().responseBody!!
        assert(updated.firstName == "UpdatedName")
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
            .expectBody(AuthorDTO::class.java)
            .returnResult().responseBody!!

        webTestClient.delete().uri("/api/authors/${created.id}")
            .exchange()
            .expectStatus().isNoContent

        webTestClient.get().uri("/api/authors/${created.id}")
            .exchange()
            .expectStatus().isNotFound
    }
}
