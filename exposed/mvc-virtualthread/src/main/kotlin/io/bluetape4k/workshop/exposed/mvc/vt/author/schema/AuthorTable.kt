package io.bluetape4k.workshop.exposed.mvc.vt.author.schema

import org.jetbrains.exposed.v1.core.Table

object AuthorTable : Table("authors") {
    val id = long("id").autoIncrement()
    val firstName = varchar("first_name", 100)
    val lastName = varchar("last_name", 100)
    val email = varchar("email", 255).uniqueIndex()
    override val primaryKey = PrimaryKey(id)
}
