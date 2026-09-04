package io.bluetape4k.workshop.exposed.mvc.jdbc.author.repository

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.exposed.jdbc.repository.findCursorPage as findCursorPageExtension
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.BookDTO
import io.bluetape4k.exposed.core.ExposedCursorPage
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.CreateBookRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.mapper.toBookDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.schema.AuthorTable
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.schema.BookTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.springframework.stereotype.Repository

/**
 * bluetape4k [LongJdbcRepository]를 기반으로 하는 Book CRUD repository이다.
 *
 * [LongJdbcRepository]에서 표준 CRUD(findAll, findById, count, existsById, deleteById, findPage, batchInsert)를 상속한다.
 * 여기서는 [table], [extractId], [ResultRow.toEntity]만 정의한다.
 */
@Repository
class BookRepository : LongJdbcRepository<BookDTO> {

    override val table = BookTable

    override fun extractId(entity: BookDTO) = entity.id

    override fun ResultRow.toEntity() = toBookDTO()

    /**
     * 기본 키 keyset과 `pageSize + 1` sentinel을 사용하는 cursor 조회입니다.
     * offset 기반 [findPage]와 별도의 예제로 유지하여 기존 호출자 ABI를 보존합니다.
     */
    fun findCursorPage(
        pageSize: Int,
        cursor: Long? = null,
        sortOrder: SortOrder = SortOrder.ASC,
    ): ExposedCursorPage<BookDTO, Long> =
        this.findCursorPageExtension(
            pageSize = pageSize,
            cursor = cursor,
            sortOrder = sortOrder,
        )

    /**
     * 지정한 author가 쓴 모든 book을 반환한다.
     *
     * [LongJdbcRepository]의 [findBy]를 사용하므로 수동 selectAll boilerplate가 필요하지 않다.
     */
    fun findByAuthorId(authorId: Long): List<BookDTO> =
        // vararg findBy는 lambda 주위에 명시적 괄호가 필요하다.
        findBy({ BookTable.authorId eq EntityID(authorId, AuthorTable) })

    /**
     * 새 book을 insert하고 영속화된 [BookDTO]를 반환한다.
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
