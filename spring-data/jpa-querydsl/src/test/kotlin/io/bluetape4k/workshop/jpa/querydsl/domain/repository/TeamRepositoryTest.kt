package io.bluetape4k.workshop.jpa.querydsl.domain.repository

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jpa.querydsl.domain.AbstractDomainTest
import io.bluetape4k.workshop.jpa.querydsl.services.InitMemberService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager

class TeamRepositoryTest(
    @Autowired private val teamRepo: TeamRepository,
    @Autowired initMemberService: InitMemberService,
    @Autowired tem: TestEntityManager,
): AbstractDomainTest(initMemberService = initMemberService, tem = tem) {

    @Test
    fun `find all teams`() {
        val teams = teamRepo.findAll().onEach { log.debug { "team=$it" } }
        
        teams.shouldNotBeEmpty()
        teams.all { it.members.isNotEmpty() }.shouldBeTrue()
    }
}
