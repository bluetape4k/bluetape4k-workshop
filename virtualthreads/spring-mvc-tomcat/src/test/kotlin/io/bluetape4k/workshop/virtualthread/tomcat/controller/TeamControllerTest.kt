package io.bluetape4k.workshop.virtualthread.tomcat.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.virtualthread.tomcat.AbstractVirtualThreadMvcTest
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.TeamDTO
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.returnResult

class TeamControllerTest: AbstractVirtualThreadMvcTest() {

    companion object: KLoggingChannel()

    @Test
    fun `get all teams`() = runSuspendIO {
        val teams = webTestClient
            .get()
            .uri("/team")
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<TeamDTO>()
            .responseBody.asFlow().toList()

        teams.shouldNotBeEmpty()
        teams.forEach {
            log.debug { "team: $it" }
        }
    }

    @Test
    fun `get team by id`() = runSuspendIO {
        val team = webTestClient
            .get()
            .uri("/team/1")
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<TeamDTO>().responseBody
            .awaitSingle()

        team.id shouldBeEqualTo 1L
    }

    @Test
    fun `get team by name`() = runSuspendIO {
        val team = webTestClient
            .get()
            .uri("/team/name/teamA")
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<TeamDTO>().responseBody
            .awaitSingle()

        team.name shouldBeEqualTo "teamA"
    }
}
