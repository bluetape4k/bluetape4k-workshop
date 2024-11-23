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

    fun search(condition: MemberSearchCondition): List<MemberWithTeamDTO>

    fun searchPageSimple(condition: MemberSearchCondition, page: Pageable): List<MemberWithTeamDTO>

    fun searchPageComplex(condition: MemberSearchCondition, page: Pageable): List<MemberWithTeamDTO>

    fun searchPageExtremeCountQuery(condition: MemberSearchCondition, page: Pageable): List<MemberWithTeamDTO>

}

class MemberRepositoryImpl: QuerydslRepositorySupport(Member::class.java), MemberRepositoryCustom {

    private val queryFactory get() = JPAQueryFactory(entityManager)

    private val qmember = QMember.member
    private val qteam = QTeam.team

    override fun search(condition: MemberSearchCondition): List<MemberWithTeamDTO> {
        // Projections.constructor 를 이용하여 DTO를 바로 제공한다
        val projection = Projections.constructor(
            MemberWithTeamDTO::class.java,
            qmember.id,
            qmember.name,
            qmember.age,
            qteam.id,
            qteam.name
        )

        val whereClauses = listOfNotNull(
            condition.memberName?.let { qmember.name.eq(it) },
            condition.teamName?.let { qteam.name.eq(it) },
            condition.ageGoe?.let { qmember.age.goe(it) },
            condition.ageLoe?.let { qmember.age.loe(it) },
        )

        return queryFactory
            .select(projection)
            .from(qmember)
            .leftJoin(qmember.team, qteam)
            .where(*whereClauses.toTypedArray())
            .fetch()
    }

    override fun searchPageSimple(condition: MemberSearchCondition, page: Pageable): List<MemberWithTeamDTO> {
        TODO("구현 중")
    }

    override fun searchPageComplex(condition: MemberSearchCondition, page: Pageable): List<MemberWithTeamDTO> {
        TODO("구현 중")
    }

    override fun searchPageExtremeCountQuery(
        condition: MemberSearchCondition,
        page: Pageable,
    ): List<MemberWithTeamDTO> {
        TODO("구현 중")
    }

}
