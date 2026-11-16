package io.bluetape4k.workshop.observability.advanced.model

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table

/**
 * `users` table 을 위한 Exposed SQL DSL table definition 입니다.
 */
object UserTable: Table("users") {
    val id = long("id")
    val name = varchar("name", 100)
    val email = varchar("email", 200)

    override val primaryKey = PrimaryKey(id)
}

/**
 * [ResultRow] 를 [UserRecord] domain object 로 변환합니다.
 */
fun ResultRow.toUser(): UserRecord = UserRecord(
    id = this[UserTable.id],
    name = this[UserTable.name],
    email = this[UserTable.email],
)
