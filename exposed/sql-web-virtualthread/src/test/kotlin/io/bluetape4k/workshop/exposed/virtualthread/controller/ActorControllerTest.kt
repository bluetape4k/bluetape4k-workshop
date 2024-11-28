package io.bluetape4k.workshop.exposed.virtualthread.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpDelete
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.spring.tests.httpPost
import io.bluetape4k.workshop.exposed.virtualthread.AbstractExposedTest
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.ActorDTO
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
): AbstractExposedTest() {

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

        val actor = client.httpGet("/actors/$id")
            .expectBody<ActorDTO>().returnResult().responseBody

        log.debug { "actor=$actor" }

        actor.shouldNotBeNull()
        actor.id shouldBeEqualTo id
    }

    @Test
    fun `find actors by name`() {
        val lastName = "Depp"

        val actors = client.httpGet("/actors?lastName=$lastName")
            .expectBodyList<ActorDTO>().returnResult().responseBody

        log.debug { "actors=$actors" }

        actors.shouldNotBeNull()
        actors.size shouldBeEqualTo 1

        val firstName = "Angelina"
        val angelinas = client.httpGet("/actors?firstName=$firstName")
            .expectBodyList<ActorDTO>().returnResult().responseBody

        log.debug { "angelinas=$angelinas" }
        angelinas.shouldNotBeNull()
        angelinas shouldHaveSize 2
    }

    @Test
    fun `create actor`() {
        val actor = newActor()

        val newActor = client.httpPost("/actors", actor)
            .expectBody<ActorDTO>()
            .returnResult().responseBody!!

        newActor shouldBeEqualTo actor.copy(id = newActor.id)
    }

    @Test
    fun `delete actor`() {
        val actor = newActor()

        val newActor = client.httpPost("/actors", actor)
            .expectBody<ActorDTO>()
            .returnResult().responseBody!!

        val deletedCount = client.httpDelete("/actors/${newActor.id}")
            .expectBody<Int>()
            .returnResult().responseBody!!

        deletedCount shouldBeEqualTo 1
    }
}
