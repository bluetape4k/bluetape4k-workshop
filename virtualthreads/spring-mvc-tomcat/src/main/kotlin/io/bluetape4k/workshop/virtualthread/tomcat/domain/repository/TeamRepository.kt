package io.bluetape4k.workshop.virtualthread.tomcat.domain.repository

import io.bluetape4k.workshop.virtualthread.tomcat.domain.model.Team
import org.springframework.data.jpa.repository.JpaRepository

/**
 * [Team] JPA Repository
 */
interface TeamRepository: JpaRepository<Team, Long> {

    /**
     * Team 이름으로 조회
     */
    fun findByName(name: String): Team?

    /**
     * Team 이름으로 조회 (Like 검색)
     */
    fun findAllByNameLikeIgnoreCase(nameToMatch: String): List<Team>
}
