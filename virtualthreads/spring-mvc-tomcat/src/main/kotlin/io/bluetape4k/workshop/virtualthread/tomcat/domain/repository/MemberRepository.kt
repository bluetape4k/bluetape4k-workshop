package io.bluetape4k.workshop.virtualthread.tomcat.domain.repository

import com.querydsl.core.types.Projections
import com.querydsl.jpa.impl.JPAQueryFactory
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.MemberSearchCondition
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.MemberWithTeamDTO
import io.bluetape4k.workshop.virtualthread.tomcat.domain.model.Member
import io.bluetape4k.workshop.virtualthread.tomcat.domain.model.QMember
import io.bluetape4k.workshop.virtualthread.tomcat.domain.model.QTeam
import io.bluetape4k.workshop.virtualthread.tomcat.domain.model.Team
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.support.QuerydslRepositorySupport
import org.springframework.data.querydsl.QuerydslPredicateExecutor

interface MemberRepository: JpaRepository<Member, Long>,
                            MemberRepositoryCustom,
                            QuerydslPredicateExecutor<Member> {

    fun findAllByName(name: String): List<Member>
    fun findAllByTeam(team: Team): List<Member>
}


interface MemberRepositoryCustom {

    /**
     * 검색 조건에 맞는 회원과 팀 정보를 조회합니다.
     */
    fun search(condition: MemberSearchCondition): List<MemberWithTeamDTO>

    /**
     * 검색 조건에 맞는 회원과 팀 정보를 단순 offset/limit 기반으로 조회합니다.
     */
    fun searchPageSimple(condition: MemberSearchCondition, page: Pageable): List<MemberWithTeamDTO>

    /**
     * 검색 조건에 맞는 회원과 팀 정보를 복합 페이지 조회 전략으로 조회합니다.
     */
    fun searchPageComplex(condition: MemberSearchCondition, page: Pageable): List<MemberWithTeamDTO>

    /**
     * 검색 조건에 맞는 회원과 팀 정보를 count 최적화 전략과 동일한 결과 계약으로 조회합니다.
     */
    fun searchPageExtremeCountQuery(condition: MemberSearchCondition, page: Pageable): List<MemberWithTeamDTO>

}

class MemberRepositoryImpl: QuerydslRepositorySupport(Member::class.java), MemberRepositoryCustom {

    private val queryFactory get() = JPAQueryFactory(entityManager)

    private val qmember = QMember.member
    private val qteam = QTeam.team

    private fun projection() = Projections.constructor(
        MemberWithTeamDTO::class.java,
        qmember.id,
        qmember.name,
        qmember.age,
        qteam.id,
        qteam.name
    )

    private fun whereClauses(condition: MemberSearchCondition) = listOfNotNull(
        condition.memberName?.let { qmember.name.eq(it) },
        condition.teamName?.let { qteam.name.eq(it) },
        condition.ageGoe?.let { qmember.age.goe(it) },
        condition.ageLoe?.let { qmember.age.loe(it) },
    ).toTypedArray()

    private fun pagedSearch(condition: MemberSearchCondition, page: Pageable): List<MemberWithTeamDTO> {
        return queryFactory
            .select(projection())
            .from(qmember)
            .leftJoin(qmember.team(), qteam)
            .where(*whereClauses(condition))
            .orderBy(qmember.id.asc())
            .offset(page.offset)
            .limit(page.pageSize.toLong())
            .fetch()
    }

    override fun search(condition: MemberSearchCondition): List<MemberWithTeamDTO> {
        return queryFactory
            .select(projection())
            .from(qmember)
            .leftJoin(qmember.team(), qteam)
            .where(*whereClauses(condition))
            .fetch()
    }

    override fun searchPageSimple(condition: MemberSearchCondition, page: Pageable): List<MemberWithTeamDTO> {
        return pagedSearch(condition, page)
    }

    override fun searchPageComplex(condition: MemberSearchCondition, page: Pageable): List<MemberWithTeamDTO> {
        return pagedSearch(condition, page)
    }

    override fun searchPageExtremeCountQuery(
        condition: MemberSearchCondition,
        page: Pageable,
    ): List<MemberWithTeamDTO> {
        return pagedSearch(condition, page)
    }

}
