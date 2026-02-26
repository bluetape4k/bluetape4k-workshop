package io.bluetape4k.workshop.jpa.querydsl.domain.repository

import io.bluetape4k.workshop.jpa.querydsl.domain.AbstractDomainTest
import org.amshove.kluent.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class TeamRepositoryTest(
    @param:Autowired private val teamRepo: TeamRepository,
): AbstractDomainTest() {

    @Test
    fun `find all teams`() {
        val teams = teamRepo.findAll()
        teams.shouldNotBeEmpty()

        teams.forEach { team ->
            team.members.shouldNotBeEmpty()
        }
    }
}
