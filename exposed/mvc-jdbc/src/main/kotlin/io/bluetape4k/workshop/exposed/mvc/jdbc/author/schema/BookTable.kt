package io.bluetape4k.workshop.exposed.mvc.jdbc.author.schema

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Books table backed by JetBrains [LongIdTable].
 *
 * Uses standard Exposed [LongIdTable] (no auditing) to contrast with [AuthorTable]
 * which uses bluetape4k [AuditableLongIdTable].
 * [authorId] is a typed FK reference to [AuthorTable].
 */
object BookTable : LongIdTable("books") {
    val title = varchar("title", 200)
    val publishDate = varchar("publish_date", 20)
    val authorId = reference("author_id", AuthorTable)
}
