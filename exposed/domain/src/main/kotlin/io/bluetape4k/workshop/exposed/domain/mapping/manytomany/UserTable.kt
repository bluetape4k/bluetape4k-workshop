package io.bluetape4k.workshop.exposed.domain.mapping.manytomany

import io.bluetape4k.exposed.dao.id.TimebasedUUIDTable
import io.bluetape4k.workshop.exposed.domain.mapping.manytomany.UserStatus.UNKNOWN
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * User Table
 *
 * @see User
 * @see GroupTable
 * @see MemberTable
 */
object UserTable: TimebasedUUIDTable() {
    val firstName = varchar("first_name", 50)
    val lastName = varchar("last_name", 50)
    val username = varchar("username", 50)
    val status = enumeration<UserStatus>("status").default(UNKNOWN)
    val createAt = datetime("created_at").defaultExpression(CurrentDateTime)
}
