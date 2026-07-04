package io.bluetape4k.workshop.jpa.querydsl.domain.repository

import io.bluetape4k.workshop.jpa.querydsl.domain.AbstractDomainTest
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.workshop.jpa.querydsl.services.InitMemberService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager

class TeamRepositoryTest(
    @param:Autowired private val teamRepo: TeamRepository,
    @param:Autowired initMemberService: InitMemberService,
    @param:Autowired tem: TestEntityManager,
) : AbstractDomainTest(
    initMemberService = initMemberService,
    tem = tem,
) {

    @Test
    fun `find all teams`() {
        val teams = teamRepo.findAll()
        teams.shouldNotBeEmpty()

        teams.forEach { team ->
            team.members.shouldNotBeEmpty()
        }
    }
}
