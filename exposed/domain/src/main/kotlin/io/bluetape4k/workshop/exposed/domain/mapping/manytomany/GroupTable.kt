package io.bluetape4k.workshop.exposed.domain.mapping.manytomany

import io.bluetape4k.exposed.dao.id.TimebasedUUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * Group Table
 *
 * @see MemberTable
 * @see UserTable
 * @see Group
 */
object GroupTable: TimebasedUUIDTable() {
    val name = varchar("name", 50)
    val description = text("description")
    val createAt = datetime("created_at").defaultExpression(CurrentDateTime)

    val owner = reference("owner_id", UserTable)
}
