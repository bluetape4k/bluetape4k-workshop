package io.bluetape4k.workshop.exposed.domain.mapping.manytomany

import io.bluetape4k.exposed.dao.id.TimebasedUUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * Member Table (UserTable, GroupTable의 Many-to-Many 관계를 나타내는 테이블)
 *
 * @see UserTable
 * @see GroupTable
 */
object MemberTable: TimebasedUUIDTable() {

    val user = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val group = reference("group_id", GroupTable, onDelete = ReferenceOption.CASCADE)

    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
}
