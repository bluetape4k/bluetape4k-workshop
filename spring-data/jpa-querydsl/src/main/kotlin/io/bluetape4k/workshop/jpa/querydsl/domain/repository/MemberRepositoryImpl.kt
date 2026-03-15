package io.bluetape4k.workshop.jpa.querydsl.domain.repository

import com.querydsl.core.types.Projections
import com.querydsl.core.types.Order
import com.querydsl.core.types.OrderSpecifier
import com.querydsl.core.types.dsl.BooleanExpression
import com.querydsl.jpa.impl.JPAQueryFactory
import io.bluetape4k.workshop.jpa.querydsl.domain.dto.MemberSearchCondition
import io.bluetape4k.workshop.jpa.querydsl.domain.dto.MemberTeamDto
import io.bluetape4k.workshop.jpa.querydsl.domain.model.Member
import io.bluetape4k.workshop.jpa.querydsl.domain.model.QMember
import io.bluetape4k.workshop.jpa.querydsl.domain.model.QTeam
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.support.QuerydslRepositorySupport
import org.springframework.data.support.PageableExecutionUtils

/**
 * [Member] 검색 조건을 Querydsl 기반으로 조합하고, DTO와 페이지 결과를 함께 제공한다.
 */
class MemberRepositoryImpl: QuerydslRepositorySupport(Member::class.java), MemberRepositoryCustom {

    private val queryFactory get() = JPAQueryFactory(entityManager)

    private val qmember = QMember.member
    private val qteam = QTeam.team

    override fun search(condition: MemberSearchCondition): List<MemberTeamDto> {
        return searchQuery(condition).fetch()
    }

    //    fun List<Tuple>.toMemberTeamDto(): List<MemberTeamDto> {
    //        return map { tuple ->
    //            MemberTeamDto(
    //                member = MemberDto(tuple.get(qmember.id)!!, tuple.get(qmember.name)!!, tuple.get(qmember.age)!!),
    //                team = TeamDto(tuple.get(qteam.id) ?: 0, tuple.get(qteam.name) ?: "")
    //            )
    //        }
    //    }


    override fun searchPageSimple(condition: MemberSearchCondition, pageable: Pageable): Page<MemberTeamDto> {
        val content = pagedSearchQuery(condition, pageable).fetch()

        @Suppress("DEPRECATION")
        val total = countQuery(condition).fetchCount()

        return PageImpl(content, pageable, total)
    }

    override fun searchPageComplex(condition: MemberSearchCondition, pageable: Pageable): Page<MemberTeamDto> {
        val content = pagedSearchQuery(condition, pageable).fetch()

        return PageableExecutionUtils.getPage(content, pageable) {
            @Suppress("DEPRECATION")
            countQuery(condition).fetchCount()
        }
    }

    override fun searchPageExtremeCountQuery(
        condition: MemberSearchCondition,
        pageable: Pageable,
    ): Page<MemberTeamDto> {
        val content = pagedSearchQuery(condition, pageable).fetch()

        return PageableExecutionUtils.getPage(content, pageable) {
            @Suppress("DEPRECATION")
            countQuery(condition)
                .select(qmember.id.countDistinct())
                .fetchOne() ?: 0L
        }
    }

    private fun memberTeamProjection() = Projections.constructor(
        MemberTeamDto::class.java,
        qmember.id,
        qmember.name,
        qmember.age,
        qteam.id,
        qteam.name
    )

    private fun searchPredicates(condition: MemberSearchCondition): Array<BooleanExpression> =
        listOfNotNull(
            condition.memberName?.let { qmember.name.eq(it) },
            condition.teamName?.let { qteam.name.eq(it) },
            condition.ageGoe?.let { qmember.age.goe(it) },
            condition.ageLoe?.let { qmember.age.loe(it) },
        ).toTypedArray()

    private fun searchQuery(condition: MemberSearchCondition) =
        queryFactory
            .select(memberTeamProjection())
            .from(qmember)
            .leftJoin(qmember.team(), qteam)
            .where(*searchPredicates(condition))

    private fun countQuery(condition: MemberSearchCondition) =
        queryFactory
            .select(qmember)
            .from(qmember)
            .leftJoin(qmember.team(), qteam)
            .where(*searchPredicates(condition))

    private fun pagedSearchQuery(condition: MemberSearchCondition, pageable: Pageable) =
        searchQuery(condition).apply {
            pageable.sort.forEach { order -> orderBy(orderSpecifier(order)) }
            offset(pageable.offset)
            limit(pageable.pageSize.toLong())
        }

    private fun orderSpecifier(order: org.springframework.data.domain.Sort.Order): OrderSpecifier<*> {
        val direction = if (order.isAscending) Order.ASC else Order.DESC
        return when (order.property) {
            "id" -> OrderSpecifier(direction, qmember.id)
            "name" -> OrderSpecifier(direction, qmember.name)
            "age" -> OrderSpecifier(direction, qmember.age)
            "teamId" -> OrderSpecifier(direction, qteam.id)
            "teamName" -> OrderSpecifier(direction, qteam.name)
            else -> throw IllegalArgumentException("Unsupported sort property: ${order.property}")
        }
    }
}
