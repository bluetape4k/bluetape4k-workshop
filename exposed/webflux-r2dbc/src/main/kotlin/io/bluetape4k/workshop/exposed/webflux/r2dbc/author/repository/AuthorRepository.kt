package io.bluetape4k.workshop.exposed.webflux.r2dbc.author.repository

import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.AuthorDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.CreateAuthorRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.schema.AuthorTable
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
class AuthorRepository {

    fun findAll(): Flow<AuthorDTO> =
        AuthorTable.selectAll().map { it.toAuthorDTO() }

    fun findById(id: Long): Flow<AuthorDTO> =
        AuthorTable.selectAll().where { AuthorTable.id eq id }.map { it.toAuthorDTO() }

    suspend fun findByIdOrNull(id: Long): AuthorDTO? =
        findById(id).firstOrNull()

    suspend fun insert(req: CreateAuthorRequest): Long {
        val stmt = AuthorTable.insert {
            it[firstName] = req.firstName
            it[lastName] = req.lastName
            it[email] = req.email
        }
        return stmt[AuthorTable.id]
    }

    suspend fun update(id: Long, req: CreateAuthorRequest): Int =
        AuthorTable.update({ AuthorTable.id eq id }) {
            it[firstName] = req.firstName
            it[lastName] = req.lastName
            it[email] = req.email
        }

    suspend fun delete(id: Long): Int =
        AuthorTable.deleteWhere { AuthorTable.id eq id }

    private fun ResultRow.toAuthorDTO() = AuthorDTO(
        id = this[AuthorTable.id],
        firstName = this[AuthorTable.firstName],
        lastName = this[AuthorTable.lastName],
        email = this[AuthorTable.email],
    )
}
