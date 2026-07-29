package io.bluetape4k.workshop.exposed.webflux.r2dbc.author.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.AuthorDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.CreateAuthorRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.repository.AuthorRepository
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.repository.BookRepository
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.springframework.stereotype.Service

@Service
class AuthorService(
    private val authorRepo: AuthorRepository,
    private val bookRepo: BookRepository,
    private val db: R2dbcDatabase,
) {
    companion object : KLoggingChannel()

    suspend fun findAll(): List<AuthorDTO> = suspendTransaction(db = db) {
        authorRepo.findAll().toList()
    }

    suspend fun findById(id: Long): AuthorDTO = suspendTransaction(db = db) {
        authorRepo.findByIdOrNull(id)
            ?: throw NoSuchElementException("Author $id not found")
    }

    suspend fun create(req: CreateAuthorRequest): AuthorDTO = suspendTransaction(db = db) {
        val newId = authorRepo.insert(req)
        authorRepo.findByIdOrNull(newId)
            ?: throw NoSuchElementException("Author $newId not found after insert")
    }

    suspend fun update(id: Long, req: CreateAuthorRequest): AuthorDTO = suspendTransaction(db = db) {
        val rows = authorRepo.update(id, req)
        if (rows == 0) throw NoSuchElementException("Author $id not found")
        authorRepo.findByIdOrNull(id)
            ?: throw NoSuchElementException("Author $id not found after update")
    }

    suspend fun delete(id: Long) = suspendTransaction(db = db) {
        // 같은 connection에서 열린 cursor와 mutation이 겹치지 않도록 DELETE 전에 모든 book을 먼저 수집한다.
        val books = bookRepo.findByAuthorId(id).toList()
        books.forEach { bookRepo.delete(it.id) }
        val rows = authorRepo.delete(id)
        if (rows == 0) throw NoSuchElementException("Author $id not found")
    }
}
