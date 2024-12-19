package io.bluetape4k.workshop.exposed.domain

import io.bluetape4k.workshop.exposed.dto.UserDTO
import org.jetbrains.exposed.sql.ResultRow

fun ResultRow.toUser(): User {
    return User(
        id = UserId(this@toUser[UserEntity.id].value),
        name = this@toUser[UserEntity.name],
        age = this@toUser[UserEntity.age],
    )
}

fun User.toUserDTO(): UserDTO = UserDTO(
    id = id.value,
    name = name,
    age = age,
)
