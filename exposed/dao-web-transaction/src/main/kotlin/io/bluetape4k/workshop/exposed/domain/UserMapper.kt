package io.bluetape4k.workshop.exposed.domain

import io.bluetape4k.workshop.exposed.dto.UserDTO
import org.jetbrains.exposed.sql.ResultRow

fun ResultRow.toUser(): User {
    return User(
        id = UserId(this@toUser[UserTable.id].value),
        name = this@toUser[UserTable.name],
        age = this@toUser[UserTable.age],
    )
}

fun User.toUserDTO(): UserDTO = UserDTO(
    id = id.value,
    name = name,
    age = age,
)
