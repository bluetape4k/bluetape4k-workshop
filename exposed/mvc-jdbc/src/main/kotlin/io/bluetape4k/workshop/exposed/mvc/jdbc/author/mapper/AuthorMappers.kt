package io.bluetape4k.workshop.exposed.mvc.jdbc.author.mapper

import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.AuthorDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.schema.AuthorTable
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.schema.BookTable
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toAuthorDTO() = AuthorDTO(
    id = this[AuthorTable.id].value,           // AuditableLongIdTable의 EntityID<Long>을 Long으로 변환한다.
    firstName = this[AuthorTable.firstName],
    lastName = this[AuthorTable.lastName],
    email = this[AuthorTable.email],
)

fun ResultRow.toBookDTO() = BookDTO(
    id = this[BookTable.id].value,             // LongIdTable의 EntityID<Long>을 Long으로 변환한다.
    title = this[BookTable.title],
    publishDate = this[BookTable.publishDate],
    authorId = this[BookTable.authorId].value, // EntityID<Long> FK를 Long으로 변환한다.
)
