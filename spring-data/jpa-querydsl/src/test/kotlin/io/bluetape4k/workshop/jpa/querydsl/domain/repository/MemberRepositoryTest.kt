package io.bluetape4k.workshop.jpa.querydsl.domain.repository

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jpa.querydsl.domain.AbstractDomainTest
import io.bluetape4k.workshop.jpa.querydsl.domain.dto.MemberSearchCondition
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class MemberRepositoryTest(
    @Autowired private val memberRepo: MemberRepository,
): AbstractDomainTest() {

    companion object: KLogging()

    @Test
    fun `context loading`() {
        memberRepo.shouldNotBeNull()
    }

    @Test
    fun `find by search condition`() {
        val searchCond = MemberSearchCondition(memberName = "member-5")
        val memberTeamDtos = memberRepo.search(searchCond)
        memberTeamDtos.forEach {
            log.debug { it }
        }
        memberTeamDtos shouldHaveSize 1
    }
}
