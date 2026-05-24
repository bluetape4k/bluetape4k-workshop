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
 * Author CRUD repository backed by bluetape4k [LongAuditableJdbcRepository].
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
 * `batchInsert()`, `auditedUpdateById()` are all inherited — no boilerplate needed.
 */
@Repository
class AuthorRepository : LongAuditableJdbcRepository<AuthorDTO, AuthorTable> {

    override val table = AuthorTable

    override fun extractId(entity: AuthorDTO) = entity.id

    override fun ResultRow.toEntity() = toAuthorDTO()

    /**
     * Inserts a new author and returns the persisted [AuthorDTO].
     *
     * [insertAndGetId] retrieves the generated Long PK in one round-trip.
     * Audit columns (`createdBy`, `createdAt`) are set automatically by
     * [AuthorTable]'s clientDefault and DB default expression.
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
