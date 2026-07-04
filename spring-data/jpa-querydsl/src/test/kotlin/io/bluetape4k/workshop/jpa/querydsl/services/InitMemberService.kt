package io.bluetape4k.workshop.jpa.querydsl.services

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jpa.querydsl.domain.model.Member
import io.bluetape4k.workshop.jpa.querydsl.domain.model.Team
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class InitMemberService(
    private val em: EntityManager,
) {

    companion object : KLogging()

    @Transactional
    fun init() {
        log.debug { "Add Sample Team and Member entity ..." }

        val teamA = Team("teamA")
        val teamB = Team("teamB")
        em.persist(teamA)
        em.persist(teamB)
        em.flush()

        repeat(100) {
            val selectedTeam = if (it % 2 == 0) teamA else teamB
            val member = Member("member-$it", it, selectedTeam)
            em.persist(member)
        }
        em.flush()
    }
}
