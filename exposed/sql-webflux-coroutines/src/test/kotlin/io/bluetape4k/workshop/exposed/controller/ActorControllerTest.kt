package io.bluetape4k.workshop.exposed.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedSqlTest
import io.bluetape4k.workshop.exposed.delete
import io.bluetape4k.workshop.exposed.domain.dto.ActorDTO
import io.bluetape4k.workshop.exposed.get
import io.bluetape4k.workshop.exposed.post
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.expectBodyList

class ActorControllerTest(
    @Autowired private val client: WebTestClient,
): AbstractExposedSqlTest() {

    companion object: KLogging() {
        private fun newActor(): ActorDTO = ActorDTO(
            faker.name().firstName(),
            faker.name().lastName(),
            faker.date().birthdayLocalDate().toString()
        )
    }

    @Test
    fun `get actor by id`() {
        val id = 1

        val actor = client.get("/actors/$id")
            .expectBody<ActorDTO>().returnResult().responseBody

        log.debug { "actor=$actor" }

        actor.shouldNotBeNull()
        actor.id shouldBeEqualTo id
    }

    @Test
    fun `find actors by name`() {
        val lastName = "Depp"

        val actors = client.get("/actors?lastName=$lastName")
            .expectBodyList<ActorDTO>().returnResult().responseBody

        log.debug { "actors=$actors" }

        actors.shouldNotBeNull()
        actors.size shouldBeEqualTo 1

        val firstName = "Angelina"
        val angelinas = client.get("/actors?firstName=$firstName")
            .expectBodyList<ActorDTO>().returnResult().responseBody

        log.debug { "angelinas=$angelinas" }
        angelinas.shouldNotBeNull()
        angelinas shouldHaveSize 2
    }

    @Test
    fun `create actor`() {
        val actor = newActor()

        val newActor = client.post("/actors", actor)
            .expectBody<ActorDTO>()
            .returnResult().responseBody!!

        newActor shouldBeEqualTo actor.copy(id = newActor.id)
    }

    @Test
    fun `delete actor`() {
        val actor = newActor()

        val newActor = client.post("/actors", actor)
            .expectBody<ActorDTO>()
            .returnResult().responseBody!!

        val deletedCount = client.delete("/actors/${newActor.id}")
            .expectBody<Int>()
            .returnResult().responseBody!!

        deletedCount shouldBeEqualTo 1
    }
}
