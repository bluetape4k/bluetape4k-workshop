package io.bluetape4k.workshop.virtualthread.tomcat.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.virtualthread.tomcat.AbstractVirtualThreadMvcTest
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.TeamDTO
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.expectBodyList

class TeamRepositoryTest(
    @Autowired private val client: WebTestClient,
): AbstractVirtualThreadMvcTest() {

    companion object: KLogging()

    @Test
    fun `get all teams`() {
        val teams = client.get("/team")
            .expectBodyList<TeamDTO>().returnResult().responseBody!!

        teams.shouldNotBeEmpty()
        teams.forEach {
            log.debug { "team: $it" }
        }
    }

    @Test
    fun `get team by id`() {
        val team = client.get("/team/1")
            .expectBody<TeamDTO>().returnResult().responseBody!!
        team.id shouldBeEqualTo 1L
    }

    @Test
    fun `get team by name`() {
        val team = client.get("/team/name/teamA")
            .expectBody<TeamDTO>().returnResult().responseBody!!
        team.name shouldBeEqualTo "teamA"
    }
}
