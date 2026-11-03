package io.bluetape4k.workshop.exposed.mvc.jdbc.author.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.AuthorDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.AuthorWithBooksDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.BookCursorPageResponse
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.CreateAuthorRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto.CreateBookRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.repository.AuthorRepository
import io.bluetape4k.workshop.exposed.mvc.jdbc.author.repository.BookRepository
import org.jetbrains.exposed.v1.core.SortOrder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AuthorService(
    private val authorRepo: AuthorRepository,
    private val bookRepo: BookRepository,
) {
    companion object: KLogging()

    fun findAll(): List<AuthorDTO> = authorRepo.findAll()

    fun findById(id: Long): AuthorDTO =
        authorRepo.findByIdOrNull(id) ?: throw NoSuchElementException("Author $id not found")

    fun findAllBooks(): List<BookDTO> = bookRepo.findAll()

    fun findBooksCursor(
        pageSize: Int,
        cursor: Long?,
        sortOrder: SortOrder,
    ): BookCursorPageResponse {
        val page = bookRepo.findCursorPage(pageSize = pageSize, cursor = cursor, sortOrder = sortOrder)
        return BookCursorPageResponse(
            content = page.content,
            nextCursor = page.nextCursor,
            hasNext = page.hasNext,
        )
    }

    @Transactional(readOnly = true)
    fun findBooksBy(authorId: Long): List<BookDTO> = bookRepo.findByAuthorId(authorId)

    @Transactional(readOnly = true)
    fun findWithBooks(authorId: Long): AuthorWithBooksDTO {
        val author = findById(authorId)
        val books = bookRepo.findByAuthorId(authorId)
        return AuthorWithBooksDTO(author, books)
    }

    @Transactional
    fun createAuthor(req: CreateAuthorRequest): AuthorDTO =
        authorRepo.save(req)

    @Transactional
    fun createBook(req: CreateBookRequest): BookDTO {
        authorRepo.findByIdOrNull(req.authorId)
            ?: throw NoSuchElementException("Author ${req.authorId} not found")

        return bookRepo.save(req)
    }

    @Transactional
    fun deleteAuthor(id: Long) =
        authorRepo.deleteById(id)
}
