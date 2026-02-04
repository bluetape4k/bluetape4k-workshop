package io.bluetape4k.workshop.exposed.r2dbc.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("members")
data class Member(
    val name: String,
    val age: Int,
    val email: String,
    @Id
    val id: Long? = null,
)
