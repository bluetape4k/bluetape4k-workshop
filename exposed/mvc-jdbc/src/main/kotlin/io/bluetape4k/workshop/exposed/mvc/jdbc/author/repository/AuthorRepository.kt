package io.bluetape4k.workshop.exposed.mvc.jdbc.author.repository

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.AuthorDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.CreateAuthorRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.mapper.toAuthorDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.schema.AuthorTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.springframework.stereotype.Repository

/**
 * bluetape4k [LongAuditableJdbcRepository]를 기반으로 하는 Author CRUD repository이다.
 *
 * ## bluetape4k vs raw Exposed (before/after)
 *
 * **Before (raw Exposed):**
 * ```kotlin
 * class AuthorRepository {
 *     fun findAll() = AuthorTable.selectAll().map { it.toAuthorDTO() }
 *     fun findById(id: Long) = AuthorTable.selectAll().where { AuthorTable.id eq id }.singleOrNull()?.toAuthorDTO()
 *     fun deleteById(id: Long) { AuthorTable.deleteWhere { AuthorTable.id eq id } }
 * }
 * ```
 *
 * **After (bluetape4k):**
 * ```kotlin
 * class AuthorRepository : LongAuditableJdbcRepository<AuthorDTO, AuthorTable> { ... }
 * ```
 * `findAll()`, `findById()`, `findPage()`, `count()`, `existsById()`, `deleteById()`,
 * `batchInsert()`, `auditedUpdateById()`까지 모두 상속되므로 boilerplate가 필요하지 않다.
 */
@Repository
class AuthorRepository : LongAuditableJdbcRepository<AuthorDTO, AuthorTable> {

    override val table = AuthorTable

    override fun extractId(entity: AuthorDTO) = entity.id

    override fun ResultRow.toEntity() = toAuthorDTO()

    /**
     * 새 author를 insert하고 영속화된 [AuthorDTO]를 반환한다.
     *
     * [insertAndGetId]는 생성된 Long PK를 한 번의 왕복으로 가져온다.
     * audit column(`createdBy`, `createdAt`)은 [AuthorTable]의 clientDefault와
     * DB default expression에 의해 자동 설정된다.
     */
    fun save(req: CreateAuthorRequest): AuthorDTO {
        val id = AuthorTable.insertAndGetId {
            it[firstName] = req.firstName
            it[lastName] = req.lastName
            it[email] = req.email
        }.value
        return findById(id)
    }
}
