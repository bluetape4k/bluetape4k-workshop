package io.bluetape4k.workshop.exposed.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedSqlTest
import io.bluetape4k.workshop.exposed.domain.dto.ActorDTO
import io.bluetape4k.workshop.shared.web.httpDelete
import io.bluetape4k.workshop.shared.web.httpGet
import io.bluetape4k.workshop.shared.web.httpPost
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBodyList
import org.springframework.test.web.reactive.server.returnResult

class ActorControllerTest: AbstractExposedSqlTest() {

    companion object: KLoggingChannel() {
        private fun newActor(): ActorDTO = ActorDTO(
            faker.name().firstName(),
            faker.name().lastName(),
            faker.timeAndDate().birthday().toString()
        )
    }

    @Test
    fun `get actor by id`() = runSuspendIO {
        val id = 1

        val actor = client
            .httpGet("/actors/$id")
            .expectStatus().is2xxSuccessful
            .returnResult<ActorDTO>().responseBody
            .awaitSingle()

        log.debug { "actor=$actor" }

        actor.shouldNotBeNull()
        actor.id shouldBeEqualTo id
    }

    @Test
    fun `find actors by name`() = runSuspendIO {
        val lastName = "Depp"

        val actors = client
            .httpGet("/actors?lastName=$lastName")
            .expectStatus().is2xxSuccessful
            .expectBodyList<ActorDTO>()
            .returnResult().responseBody
            .shouldNotBeNull()

        log.debug { "actors=$actors" }

        actors.shouldNotBeNull()
        actors.size shouldBeEqualTo 1

        val firstName = "Angelina"
        val angelinas = client
            .httpGet("/actors?firstName=$firstName")
            .expectStatus().is2xxSuccessful
            .expectBodyList<ActorDTO>()
            .returnResult().responseBody
            .shouldNotBeNull()

        log.debug { "angelinas=$angelinas" }
        angelinas shouldHaveSize 2
    }

    @Test
    fun `create actor`() = runSuspendIO {
        val actor = newActor()

        val newActor = client
            .httpPost("/actors", actor)
            .expectStatus().is2xxSuccessful
            .returnResult<ActorDTO>().responseBody
            .awaitSingle()

        newActor shouldBeEqualTo actor.copy(id = newActor.id)
    }

    @Test
    fun `delete actor`() = runSuspendIO {
        val actor = newActor()

        val newActor = client
            .httpPost("/actors", actor)
            .expectStatus().is2xxSuccessful
            .returnResult<ActorDTO>().responseBody
            .awaitSingle()

        val deletedCount = client
            .httpDelete("/actors/${newActor.id}")
            .expectStatus().is2xxSuccessful
            .returnResult<Int>().responseBody
            .awaitSingle()

        deletedCount shouldBeEqualTo 1
    }
}
