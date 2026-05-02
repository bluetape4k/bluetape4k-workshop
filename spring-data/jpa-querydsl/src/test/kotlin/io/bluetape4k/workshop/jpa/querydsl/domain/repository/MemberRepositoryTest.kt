package io.bluetape4k.workshop.jpa.querydsl.domain.repository

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jpa.querydsl.domain.AbstractDomainTest
import io.bluetape4k.workshop.jpa.querydsl.domain.dto.MemberSearchCondition
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

class MemberRepositoryTest(
    @param:Autowired private val memberRepo: MemberRepository,
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

    @Test
    fun `searchPageSimple 은 조건에 맞는 페이지와 전체 건수를 반환한다`() {
        val searchCond = MemberSearchCondition(teamName = "teamA", ageGoe = 20, ageLoe = 39)
        val pageable = PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "age"))

        val page = memberRepo.searchPageSimple(searchCond, pageable)

        page.totalElements shouldBeEqualTo 10L
        page.content shouldHaveSize 5
        page.content.map { it.member.age } shouldBeEqualTo listOf(38, 36, 34, 32, 30)
    }

    @Test
    fun `searchPageComplex 는 전체 건수와 페이지 내용을 함께 제공한다`() {
        val searchCond = MemberSearchCondition(teamName = "teamB", ageGoe = 11, ageLoe = 29)
        val pageable = PageRequest.of(1, 3, Sort.by(Sort.Direction.ASC, "age"))

        val page = memberRepo.searchPageComplex(searchCond, pageable)

        page.totalElements shouldBeEqualTo 10L
        page.content shouldHaveSize 3
        page.content.map { it.member.age } shouldBeEqualTo listOf(17, 19, 21)
    }

    @Test
    fun `searchPageExtremeCountQuery 는 count 최적화 경로에서도 동일한 결과를 반환한다`() {
        val searchCond = MemberSearchCondition(teamName = "teamA", ageGoe = 40, ageLoe = 49)
        val pageable = PageRequest.of(0, 4, Sort.by(Sort.Direction.ASC, "age"))

        val page = memberRepo.searchPageExtremeCountQuery(searchCond, pageable)

        page.totalElements shouldBeEqualTo 5L
        page.content shouldHaveSize 4
        page.content.map { it.member.age } shouldBeEqualTo listOf(40, 42, 44, 46)
    }
}
