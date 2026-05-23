package io.bluetape4k.workshop.exposed.webflux.r2dbc.author.repository

import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.CreateBookRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.schema.BookTable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.springframework.stereotype.Repository

@Repository
class BookRepository {

    fun findAll(): Flow<BookDTO> =
        BookTable.selectAll().map { it.toBookDTO() }

    fun findById(id: Long): Flow<BookDTO> =
        BookTable.selectAll().where { BookTable.id eq id }.map { it.toBookDTO() }

    suspend fun findByIdOrNull(id: Long): BookDTO? =
        findById(id).firstOrNull()

    fun findByAuthorId(authorId: Long): Flow<BookDTO> =
        BookTable.selectAll().where { BookTable.authorId eq authorId }.map { it.toBookDTO() }

    suspend fun insert(req: CreateBookRequest): Long {
        val stmt = BookTable.insert {
            it[title] = req.title
            it[publishDate] = req.publishDate
            it[BookTable.authorId] = req.authorId
        }
        return stmt[BookTable.id]
    }

    suspend fun update(id: Long, req: CreateBookRequest): Int =
        BookTable.update({ BookTable.id eq id }) {
            it[title] = req.title
            it[publishDate] = req.publishDate
            it[authorId] = req.authorId
        }

    suspend fun delete(id: Long): Int =
        BookTable.deleteWhere { BookTable.id eq id }

    private fun ResultRow.toBookDTO() = BookDTO(
        id = this[BookTable.id],
        title = this[BookTable.title],
        publishDate = this[BookTable.publishDate],
        authorId = this[BookTable.authorId],
    )
}
