package io.bluetape4k.workshop.jpa.querydsl.domain.repository

import io.bluetape4k.workshop.jpa.querydsl.domain.model.Team
import org.springframework.data.jpa.repository.JpaRepository

interface TeamRepository: JpaRepository<Team, Long> {

    fun findAllByName(name: String): List<Team>

    fun findAllByNameLikeIgnoreCase(nameToMatch: String): List<Team>

}
