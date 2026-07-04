package io.bluetape4k.workshop.jpa.querydsl.domain.dto

import com.querydsl.core.annotations.QueryProjection
import java.io.Serializable

data class TeamDto(
    val id: Long?,
    val name: String?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = -1760953420114915137L
    }
}


data class TeamVo @QueryProjection constructor(
    val id: Long?,
    val name: String?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 3871452397561381479L
    }
}
