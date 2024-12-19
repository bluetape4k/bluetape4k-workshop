package io.bluetape4k.workshop.exposed.domain.mapping.manytomany

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object MemberTable: UUIDTable() {

    val user = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)

    val group = reference("group_id", GroupTable, onDelete = ReferenceOption.CASCADE)

    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
}
