package io.bluetape4k.workshop.exposed.mvc.vt.author.repository

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.exposed.jdbc.virtualThreadJdbcTransactionAsync
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.AuthorDTO
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.CreateAuthorRequest
import io.bluetape4k.workshop.exposed.mvc.vt.author.mapper.toAuthorDTO
import io.bluetape4k.workshop.exposed.mvc.vt.author.schema.AuthorTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.util.concurrent.ExecutorService

@Repository
class AuthorRepository(
    private val db: Database,
    private val executor: ExecutorService,
) {
    companion object: KLogging()

    fun findAll(): VirtualFuture<List<AuthorDTO>> =
        virtualThreadJdbcTransactionAsync(executor, db) {
            AuthorTable.selectAll().map { it.toAuthorDTO() }
        }

    fun findById(id: Long): VirtualFuture<AuthorDTO?> =
        virtualThreadJdbcTransactionAsync(executor, db) {
            AuthorTable.selectAll()
                .where { AuthorTable.id eq id }
                .singleOrNull()
                ?.toAuthorDTO()
        }


    fun insert(req: CreateAuthorRequest): VirtualFuture<AuthorDTO> =
        virtualThreadJdbcTransactionAsync(executor, db) {
            val newId = AuthorTable.insert {
                it[firstName] = req.firstName
                it[lastName] = req.lastName
                it[email] = req.email
            }[AuthorTable.id]

            AuthorDTO(newId, req.firstName, req.lastName, req.email)
        }


    fun deleteById(id: Long): VirtualFuture<Unit> =
        virtualThreadJdbcTransactionAsync(executor, db) {
            AuthorTable.deleteWhere { AuthorTable.id eq id }
        }
}
