package io.bluetape4k.workshop.virtualthread.tomcat.domain.dto

import io.bluetape4k.workshop.virtualthread.tomcat.domain.model.Team
import java.io.Serializable

/**
 * 팀 정보를 전달하기 위한 DTO
 */
data class TeamDTO(
    val id: Long,
    val name: String,
): Serializable

fun Team.toTeamDTO(): TeamDTO =
    TeamDTO(
        id = this.id ?: 0,
        name = this.name
    )
