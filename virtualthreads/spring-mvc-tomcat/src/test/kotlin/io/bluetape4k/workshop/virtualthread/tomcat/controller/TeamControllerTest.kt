package io.bluetape4k.workshop.virtualthread.tomcat.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.virtualthread.tomcat.AbstractVirtualThreadMvcTest
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.TeamDTO
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.returnResult

class TeamControllerTest: AbstractVirtualThreadMvcTest() {

    companion object: KLoggingChannel()

    @Test
    fun `get all teams`() = runTest {
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
    fun `get team by id`() = runTest {
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
    fun `get team by name`() = runTest {
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
