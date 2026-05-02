package io.bluetape4k.workshop.exposed.r2dbc.domain.model

import java.io.Serializable

data class UserRecord(
    val name: String,
    val login: String,
    val email: String,
    val avatar: String? = null,
    val id: Int = -1,
): Serializable {

    fun withId(newId: Int) = copy(id = newId)

}
