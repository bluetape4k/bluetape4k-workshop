package io.bluetape4k.workshop.exposed.mvc.jdbc.author.repository

import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.AuthorDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.CreateAuthorRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.mapper.toAuthorDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.schema.AuthorTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository

@Repository
class AuthorRepository {

    fun findAll(): List<AuthorDTO> =
        AuthorTable.selectAll().map { it.toAuthorDTO() }

    fun findById(id: Long): AuthorDTO? =
        AuthorTable.selectAll()
            .where { AuthorTable.id eq id }
            .singleOrNull()
            ?.toAuthorDTO()

    fun insert(req: CreateAuthorRequest): AuthorDTO {
        val id = AuthorTable.insert {
            it[firstName] = req.firstName
            it[lastName] = req.lastName
            it[email] = req.email
        }[AuthorTable.id]
        return findById(id) ?: throw NoSuchElementException("Author $id not found after insert")
    }

    fun deleteById(id: Long) {
        AuthorTable.deleteWhere { AuthorTable.id eq id }
    }
}
