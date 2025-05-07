package io.bluetape4k.workshop.virtualthread.tomcat.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.workshop.virtualthread.tomcat.AbstractVirtualThreadMvcTest
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.TeamDTO
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

class TeamRepositoryTest(
    @Autowired private val client: WebTestClient,
): AbstractVirtualThreadMvcTest() {

    companion object: KLogging()

    @Test
    fun `get all teams`() = runTest {
        val teams = client.httpGet("/team")
            .returnResult<TeamDTO>()
            .responseBody.asFlow().toList()

        teams.shouldNotBeEmpty()
        teams.forEach {
            log.debug { "team: $it" }
        }
    }

    @Test
    fun `get team by id`() = runTest {
        val team = client.httpGet("/team/1")
            .returnResult<TeamDTO>().responseBody
            .awaitSingle()

        team.id shouldBeEqualTo 1L
    }

    @Test
    fun `get team by name`() = runTest {
        val team = client.httpGet("/team/name/teamA")
            .returnResult<TeamDTO>().responseBody
            .awaitSingle()
        
        team.name shouldBeEqualTo "teamA"
    }
}
