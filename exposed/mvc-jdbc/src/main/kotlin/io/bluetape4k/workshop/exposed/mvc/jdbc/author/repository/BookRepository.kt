package io.bluetape4k.workshop.exposed.mvc.jdbc.author.repository

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.CreateBookRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.mapper.toBookDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.schema.AuthorTable
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.schema.BookTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.springframework.stereotype.Repository

/**
 * Book CRUD repository backed by bluetape4k [LongJdbcRepository].
 *
 * Inherits standard CRUD (findAll, findById, count, existsById, deleteById, findPage, batchInsert)
 * from [LongJdbcRepository]. Only [table], [extractId], and [ResultRow.toEntity] are defined here.
 */
@Repository
class BookRepository : LongJdbcRepository<BookDTO> {

    override val table = BookTable

    override fun extractId(entity: BookDTO) = entity.id

    override fun ResultRow.toEntity() = toBookDTO()

    /**
     * Returns all books written by the given author.
     *
     * Uses [findBy] from [LongJdbcRepository] — no manual selectAll boilerplate.
     */
    fun findByAuthorId(authorId: Long): List<BookDTO> =
        // vararg findBy requires explicit parentheses around the lambda
        findBy({ BookTable.authorId eq EntityID(authorId, AuthorTable) })

    /**
     * Inserts a new book and returns the persisted [BookDTO].
     */
    fun save(req: CreateBookRequest): BookDTO {
        val id = BookTable.insertAndGetId {
            it[title] = req.title
            it[publishDate] = req.publishDate
            it[this.authorId] = EntityID(req.authorId, AuthorTable)
        }.value
        return findById(id)
    }
}
