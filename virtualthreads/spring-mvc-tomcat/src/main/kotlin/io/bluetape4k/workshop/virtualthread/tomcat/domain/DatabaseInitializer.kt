package io.bluetape4k.workshop.virtualthread.tomcat.domain

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.virtualthread.tomcat.domain.model.Member
import io.bluetape4k.workshop.virtualthread.tomcat.domain.model.Team
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.springframework.stereotype.Component

@Component
class DatabaseInitializer {

    companion object: KLogging()

    @PersistenceContext
    private lateinit var em: EntityManager

    @Transactional
    fun insertSampleData() {
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
        em.clear()
    }
}
