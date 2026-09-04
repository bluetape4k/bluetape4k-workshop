package io.bluetape4k.workshop.exposed.webflux.r2dbc.author.repository

import io.bluetape4k.exposed.core.ExposedCursorPage
import io.bluetape4k.exposed.r2dbc.repository.LongR2dbcRepository
import io.bluetape4k.exposed.r2dbc.repository.findCursorPage as findCursorPageExtension
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.CreateBookRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.schema.BookTable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.springframework.stereotype.Repository

@Repository
class BookRepository : LongR2dbcRepository<BookDTO> {

    override val table = BookTable

    override fun extractId(entity: BookDTO): Long = entity.id

    override suspend fun ResultRow.toEntity(): BookDTO = toBookDTO()

    override fun findAll(
        limit: Int?,
        offset: Long?,
        sortOrder: SortOrder,
        predicate: () -> Op<Boolean>,
    ): Flow<BookDTO> =
        BookTable.selectAll()
            .where(predicate)
            .apply {
                limit?.run { limit(limit) }
                offset?.run { offset(offset) }
            }
            .orderBy(BookTable.id, sortOrder)
            .map { it.toBookDTO() }

    fun findByIdFlow(id: Long): Flow<BookDTO> =
        BookTable.selectAll().where { BookTable.id eq id }.map { it.toBookDTO() }

    override suspend fun findByIdOrNull(id: Long): BookDTO? =
        findByIdFlow(id).firstOrNull()

    /**
     * Exposed 2.0 cursor extension을 직접 노출하는 비블로킹 예제입니다.
     * 호출자가 같은 정렬·조건과 cursor token의 서명·범위를 책임집니다.
     */
    suspend fun findCursorPage(
        pageSize: Int,
        cursor: Long? = null,
        sortOrder: SortOrder = SortOrder.ASC,
    ): ExposedCursorPage<BookDTO, Long> =
        this.findCursorPageExtension(
            pageSize = pageSize,
            cursor = cursor,
            sortOrder = sortOrder,
        )

    fun findByAuthorId(authorId: Long): Flow<BookDTO> =
        BookTable.selectAll().where { BookTable.authorId eq authorId }.map { it.toBookDTO() }

    suspend fun insert(req: CreateBookRequest): Long {
        val stmt = BookTable.insert {
            it[title] = req.title
            it[publishDate] = req.publishDate
            it[BookTable.authorId] = req.authorId
        }
        return stmt[BookTable.id].value
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
        id = this[BookTable.id].value,
        title = this[BookTable.title],
        publishDate = this[BookTable.publishDate],
        authorId = this[BookTable.authorId],
    )
}
