package io.bluetape4k.workshop.exposed.r2dbc.domain.model

import io.bluetape4k.exposed.core.HasIdentifier

data class UserRecord(
    val name: String,
    val login: String,
    val email: String,
    val avatar: String? = null,
    override val id: Int = -1,
): HasIdentifier<Int> {

    fun withId(newId: Int) = copy(id = newId)

}
