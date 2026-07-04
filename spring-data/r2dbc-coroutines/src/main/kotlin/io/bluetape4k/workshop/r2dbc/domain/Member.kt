package io.bluetape4k.workshop.r2dbc.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.io.Serializable

@Table("members")
data class Member(
    val name: String,
    val age: Int,
    val email: String,
    @Id
    val id: Long? = null,
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
