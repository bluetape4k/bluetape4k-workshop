package io.bluetape4k.workshop.virtualthread.tomcat.domain.repository

import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.virtualthread.tomcat.domain.AbstractDomainTest
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.toTeamDTO
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

@Transactional(readOnly = true)
class TeamRepositoryTest(
    @Autowired private val teamRepo: TeamRepository,
): AbstractDomainTest() {

    companion object: KLogging()

    @Test
    fun `context loading`() {
        teamRepo.shouldNotBeNull()
    }

    @Test
    fun `find all team`() {
        val teams = teamRepo.findAll()
        teams.forEach {
            log.debug { "team=${it.toTeamDTO()}" }
        }
        teams.shouldNotBeEmpty()
    }

    @Test
    fun `find all team by name`() {
        val team = teamRepo.findByName("teamA")
        team.shouldNotBeNull()
        team.name shouldBeEqualTo "teamA"
    }

    @Test
    fun `find all team in virtual thread`() {
        val task = virtualFuture {
            log.debug { "Find all team in virtual thread" }

            teamRepo.findAll().onEach {
                log.debug { "team=${it.toTeamDTO()}" }
            }
        }
        val teams = task.await()
        teams.shouldNotBeEmpty()

        teams.forEach {
            log.debug { "team=${it.toTeamDTO()}" }
        }
    }
}
