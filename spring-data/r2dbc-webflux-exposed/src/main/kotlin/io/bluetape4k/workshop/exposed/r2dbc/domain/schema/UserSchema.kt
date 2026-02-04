package io.bluetape4k.workshop.exposed.r2dbc.domain.schema

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

object UserSchema {

    object UserTable: IntIdTable("users") {
        val name = varchar("name", 255).index()
        val login = varchar("login", 255).uniqueIndex()
        val email = varchar("email", 255).uniqueIndex()
        val avatar = varchar("avatar", 255).nullable()
    }
}
