package io.bluetape4k.workshop.exposed.webflux.r2dbc.author.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.BookCursorPageResponse
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.dto.CreateBookRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.repository.AuthorRepository
import io.bluetape4k.workshop.exposed.webflux.r2dbc.author.repository.BookRepository
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.core.SortOrder
import org.springframework.stereotype.Service

@Service
class BookService(
    private val authorRepo: AuthorRepository,
    private val bookRepo: BookRepository,
    private val db: R2dbcDatabase,
) {
    companion object : KLoggingChannel()

    suspend fun findAll(): List<BookDTO> = suspendTransaction(db = db) {
        bookRepo.findAll().toList()
    }

    suspend fun findCursorPage(
        pageSize: Int,
        cursor: Long?,
        sortOrder: SortOrder,
    ): BookCursorPageResponse = suspendTransaction(db = db) {
        val page = bookRepo.findCursorPage(pageSize = pageSize, cursor = cursor, sortOrder = sortOrder)
        BookCursorPageResponse(
            content = page.content,
            nextCursor = page.nextCursor,
            hasNext = page.hasNext,
        )
    }

    suspend fun findById(id: Long): BookDTO = suspendTransaction(db = db) {
        bookRepo.findByIdOrNull(id)
            ?: throw NoSuchElementException("Book $id not found")
    }

    suspend fun findByAuthorId(authorId: Long): List<BookDTO> = suspendTransaction(db = db) {
        bookRepo.findByAuthorId(authorId).toList()
    }

    suspend fun create(req: CreateBookRequest): BookDTO = suspendTransaction(db = db) {
        authorRepo.findByIdOrNull(req.authorId)
            ?: throw NoSuchElementException("Author ${req.authorId} not found")
        val newId = bookRepo.insert(req)
        bookRepo.findByIdOrNull(newId)
            ?: throw NoSuchElementException("Book $newId not found after insert")
    }

    suspend fun update(id: Long, req: CreateBookRequest): BookDTO = suspendTransaction(db = db) {
        authorRepo.findByIdOrNull(req.authorId)
            ?: throw NoSuchElementException("Author ${req.authorId} not found")
        val rows = bookRepo.update(id, req)
        if (rows == 0) throw NoSuchElementException("Book $id not found")
        bookRepo.findByIdOrNull(id)
            ?: throw NoSuchElementException("Book $id not found after update")
    }

    suspend fun delete(id: Long) = suspendTransaction(db = db) {
        val rows = bookRepo.delete(id)
        if (rows == 0) throw NoSuchElementException("Book $id not found")
    }
}
