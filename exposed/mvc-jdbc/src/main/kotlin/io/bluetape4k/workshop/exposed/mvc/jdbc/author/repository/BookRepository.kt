package io.bluetape4k.workshop.exposed.mvc.jdbc.author.repository

import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.CreateBookRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.mapper.toBookDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.schema.BookTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository

@Repository
class BookRepository {

    fun findAll(): List<BookDTO> =
        BookTable.selectAll().map { it.toBookDTO() }

    fun findById(id: Long): BookDTO? =
        BookTable.selectAll()
            .where { BookTable.id eq id }
            .singleOrNull()
            ?.toBookDTO()

    fun findByAuthorId(authorId: Long): List<BookDTO> =
        BookTable.selectAll()
            .where { BookTable.authorId eq authorId }
            .map { it.toBookDTO() }

    fun insert(req: CreateBookRequest): BookDTO {
        val id = BookTable.insert {
            it[title] = req.title
            it[publishDate] = req.publishDate
            it[this.authorId] = req.authorId
        }[BookTable.id]
        return findById(id) ?: throw NoSuchElementException("Book $id not found after insert")
    }
}
