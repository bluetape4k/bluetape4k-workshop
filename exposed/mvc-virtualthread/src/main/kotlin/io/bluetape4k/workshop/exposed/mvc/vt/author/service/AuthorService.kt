package io.bluetape4k.workshop.exposed.mvc.vt.author.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.AuthorDTO
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.AuthorWithBooksDTO
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.CreateAuthorRequest
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.CreateBookRequest
import io.bluetape4k.workshop.exposed.mvc.vt.author.repository.AuthorRepository
import io.bluetape4k.workshop.exposed.mvc.vt.author.repository.BookRepository
import org.springframework.stereotype.Service

@Service
class AuthorService(
    private val authorRepo: AuthorRepository,
    private val bookRepo: BookRepository,
) {
    companion object : KLogging()

    fun findAll(): List<AuthorDTO> = authorRepo.findAll().get()

    fun findById(id: Long): AuthorDTO =
        authorRepo.findById(id).get() ?: throw NoSuchElementException("Author $id not found")

    fun findAllBooks(): List<BookDTO> = bookRepo.findAll().get()

    fun findBooksBy(authorId: Long): List<BookDTO> = bookRepo.findByAuthorId(authorId).get()

    fun findWithBooks(authorId: Long): AuthorWithBooksDTO {
        val author = findById(authorId)
        val books = bookRepo.findByAuthorId(authorId).get()
        return AuthorWithBooksDTO(author, books)
    }

    fun createAuthor(req: CreateAuthorRequest): AuthorDTO = authorRepo.insert(req).get()

    fun createBook(req: CreateBookRequest): BookDTO = bookRepo.insert(req).get()

    fun deleteAuthor(id: Long) = authorRepo.deleteById(id).get()
}
