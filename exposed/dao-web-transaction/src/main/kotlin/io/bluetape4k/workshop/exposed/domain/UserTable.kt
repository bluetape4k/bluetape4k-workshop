package io.bluetape4k.workshop.exposed.domain

import org.jetbrains.exposed.dao.id.LongIdTable

/**
 * User 정보를 나타내는 Table
 */
object UserTable: LongIdTable() {
    val name = varchar("name", length = 50)
    val age = integer("age")
}
