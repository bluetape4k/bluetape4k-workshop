package io.bluetape4k.workshop.exposed.domain.schema.manytomany

import io.bluetape4k.workshop.exposed.domain.model.manytomany.UserStatus
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * User Table
 */
object UserTable: UUIDTable() {
    val firstName = varchar("first_name", 50)
    val lastName = varchar("last_name", 50)
    val username = varchar("username", 50)
    val status = enumeration<UserStatus>("status").default(UserStatus.UNKNOWN)
    val createAt = datetime("created_at").defaultExpression(CurrentDateTime)
}
