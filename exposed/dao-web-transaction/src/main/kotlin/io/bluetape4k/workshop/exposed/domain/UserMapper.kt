package io.bluetape4k.workshop.exposed.domain

import io.bluetape4k.workshop.exposed.dto.UserDTO
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toUser(): User {
    val row = this
    return User(
        id = row[UserTable.id].value,
        name = row[UserTable.name],
        age = row[UserTable.age],
    )
}

fun User.toUserDTO(): UserDTO = UserDTO(
    id = id.value,
    name = name,
    age = age,
)
