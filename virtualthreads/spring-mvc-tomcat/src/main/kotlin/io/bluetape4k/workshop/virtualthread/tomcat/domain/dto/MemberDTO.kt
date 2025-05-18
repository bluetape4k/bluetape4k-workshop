package io.bluetape4k.workshop.virtualthread.tomcat.domain.dto

import io.bluetape4k.workshop.virtualthread.tomcat.domain.model.Member
import java.io.Serializable

/**
 * 회원 정보를 전달하기 위한 DTO
 */
data class MemberDTO(
    val id: Long,
    val name: String,
    val age: Int? = null,
): Serializable

/**
 * [Member] Entity를 MemberDTO로 변환하는 확장 함수
 */
fun Member.toMemberDTO(): MemberDTO =
    MemberDTO(
        id = this.id ?: 0,
        name = this.name,
        age = this.age ?: 0
    )
