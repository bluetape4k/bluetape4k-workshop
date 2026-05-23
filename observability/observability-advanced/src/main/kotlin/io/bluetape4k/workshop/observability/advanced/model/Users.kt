package io.bluetape4k.workshop.observability.advanced.model

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed SQL DSL table definition for the `users` table.
 */
object Users : Table("users") {
    val id = long("id")
    val name = varchar("name", 100)
    val email = varchar("email", 200)

    override val primaryKey = PrimaryKey(id)
}

/**
 * Converts a [ResultRow] to a [User] domain object.
 */
fun ResultRow.toUser(): User = User(
    id = this[Users.id],
    name = this[Users.name],
    email = this[Users.email],
)
