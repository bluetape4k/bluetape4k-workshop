package io.bluetape4k.workshop.virtualthread.tomcat.domain.dto

import io.bluetape4k.workshop.virtualthread.tomcat.domain.model.Member
import java.io.Serializable

data class MemberWithTeamDTO(
    val id: Long,
    val name: String,
    val age: Int? = null,
    val teamId: Long? = null,
    val teamName: String? = null,
): Serializable


fun Member.toMemberWithTeamDTO(): MemberWithTeamDTO =
    MemberWithTeamDTO(
        id = this.id ?: 0,
        name = this.name,
        age = this.age ?: 0,
        teamId = this.team?.id,
        teamName = this.team?.name
    )
