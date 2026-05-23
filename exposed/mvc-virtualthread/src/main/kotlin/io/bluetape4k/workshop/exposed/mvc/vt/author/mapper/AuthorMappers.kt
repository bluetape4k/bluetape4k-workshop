package io.bluetape4k.workshop.exposed.mvc.vt.author.mapper

import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.AuthorDTO
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.mvc.vt.author.schema.AuthorTable
import io.bluetape4k.workshop.exposed.mvc.vt.author.schema.BookTable
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toAuthorDTO() = AuthorDTO(
    id = this[AuthorTable.id],
    firstName = this[AuthorTable.firstName],
    lastName = this[AuthorTable.lastName],
    email = this[AuthorTable.email],
)

fun ResultRow.toBookDTO() = BookDTO(
    id = this[BookTable.id],
    title = this[BookTable.title],
    publishDate = this[BookTable.publishDate],
    authorId = this[BookTable.authorId],
)
