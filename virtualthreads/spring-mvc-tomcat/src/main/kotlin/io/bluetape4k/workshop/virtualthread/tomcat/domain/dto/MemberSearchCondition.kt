package io.bluetape4k.workshop.virtualthread.tomcat.domain.dto

/**
 * 회원 검색 조건을 담을 DTO
 */
data class MemberSearchCondition(
    val memberName: String? = null,
    val teamName: String? = null,
    val ageGoe: Int? = null,
    val ageLoe: Int? = null,
)
