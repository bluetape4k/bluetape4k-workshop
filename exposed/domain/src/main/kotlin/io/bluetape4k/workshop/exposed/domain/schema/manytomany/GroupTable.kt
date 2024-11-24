package io.bluetape4k.workshop.exposed.domain.schema.manytomany

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object GroupTable: UUIDTable() {
    val name = varchar("name", 50)
    val description = text("description")
    val createAt = datetime("created_at").defaultExpression(CurrentDateTime)

    val owner = reference("owner_id", UserTable)
}
