package io.bluetape4k.workshop.exposed.mvc.jdbc.author.schema

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * JetBrains [LongIdTable]을 기반으로 하는 books table이다.
 *
 * bluetape4k [AuditableLongIdTable]을 쓰는 [AuthorTable]과 대비되도록
 * 표준 Exposed [LongIdTable]을 사용하며 audit 기능은 없다.
 * [authorId]는 [AuthorTable]을 가리키는 typed FK reference이다.
 */
object BookTable : LongIdTable("books") {
    val title = varchar("title", 200)
    val publishDate = varchar("publish_date", 20)
    val authorId = reference("author_id", AuthorTable)
}
