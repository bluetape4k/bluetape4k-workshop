package io.bluetape4k.workshop.exposed.webflux.r2dbc.author.schema

import org.jetbrains.exposed.v1.core.Table

object BookTable : Table("books") {
    val id = long("id").autoIncrement()
    val title = varchar("title", 200)
    val publishDate = varchar("publish_date", 20)
    val authorId = long("author_id").references(AuthorTable.id)
    override val primaryKey = PrimaryKey(id)
}
