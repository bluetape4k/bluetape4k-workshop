package io.bluetape4k.workshop.jpa.querydsl.domain.dto

import java.io.Serializable

data class MemberSearchCondition(
    val memberName: String? = null,
    val teamName: String? = null,
    val ageGoe: Int? = null,
    val ageLoe: Int? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = -3812390850556231102L
    }
}
