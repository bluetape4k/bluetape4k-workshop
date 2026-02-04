package io.bluetape4k.workshop.exposed.r2dbc.domain.model

import io.bluetape4k.workshop.exposed.r2dbc.domain.schema.UserSchema.UserTable
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toUserRecord() = UserRecord(
    name = this[UserTable.name],
    login = this[UserTable.login],
    email = this[UserTable.email],
    avatar = this[UserTable.avatar],
    id = this[UserTable.id].value
)
