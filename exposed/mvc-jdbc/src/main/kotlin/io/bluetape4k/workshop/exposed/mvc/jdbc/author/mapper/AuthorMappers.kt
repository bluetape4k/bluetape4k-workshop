package io.bluetape4k.workshop.exposed.mvc.jdbc.author.mapper

import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.AuthorDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.schema.AuthorTable
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.schema.BookTable
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toAuthorDTO() = AuthorDTO(
    id = this[AuthorTable.id].value,           // EntityID<Long> → Long (AuditableLongIdTable)
    firstName = this[AuthorTable.firstName],
    lastName = this[AuthorTable.lastName],
    email = this[AuthorTable.email],
)

fun ResultRow.toBookDTO() = BookDTO(
    id = this[BookTable.id].value,             // EntityID<Long> → Long (LongIdTable)
    title = this[BookTable.title],
    publishDate = this[BookTable.publishDate],
    authorId = this[BookTable.authorId].value, // EntityID<Long> FK → Long
)
