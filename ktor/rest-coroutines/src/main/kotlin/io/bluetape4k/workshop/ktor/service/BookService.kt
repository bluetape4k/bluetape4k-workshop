package io.bluetape4k.workshop.ktor.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.ktor.domain.Book
import io.bluetape4k.workshop.ktor.domain.DomainError
import io.bluetape4k.workshop.ktor.repository.BookRepository
import kotlinx.coroutines.flow.Flow

/**
 * Application service for the book catalog.
 *
 * ## Behavior / Contract
 * - [create] validates all fields via bluetape4k `require*` extensions before delegating to the repository.
 * - [stream] is non-suspend and returns the repository's hot [Flow].
 */
class BookService(private val repository: BookRepository) {

    companion object : KLoggingChannel() {
        const val MAX_ID_LENGTH = 128
        const val MAX_TITLE_LENGTH = 500
        const val MAX_AUTHOR_LENGTH = 200
        val YEAR_RANGE = 1..3000
    }

    /** Returns all books in the catalog. */
    suspend fun list(): List<Book> = repository.findAll()

    /**
     * Returns the book with [id], or `null` if not found.
     *
     * @throws DomainError.NotFound if the book does not exist.
     */
    suspend fun get(id: String): Book =
        repository.findById(id) ?: throw DomainError.NotFound(id)

    /**
     * Validates and creates a new book.
     *
     * @throws IllegalArgumentException if any field is blank, too long, or year is out of range.
     * @throws DomainError.Conflict if a book with the same id already exists.
     */
    suspend fun create(book: Book): Book {
        validateBook(book)
        return repository.save(book)
    }

    /**
     * Validates and updates an existing book.
     *
     * @throws IllegalArgumentException if any field is blank or out of range.
     * @throws DomainError.NotFound if the book does not exist.
     */
    suspend fun update(id: String, book: Book): Book {
        validateBook(book)
        return repository.update(id, book)
    }

    /**
     * Deletes the book with [id].
     *
     * @throws DomainError.NotFound if the book does not exist.
     */
    suspend fun delete(id: String) = repository.delete(id)

    /**
     * Returns a hot [Flow] of books emitted on each [create] or [update].
     *
     * Live-only; no replay for late subscribers.
     */
    fun stream(): Flow<Book> = repository.stream()

    private fun validateBook(book: Book) {
        book.id.requireNotBlank("id")
        book.id.length.requireInRange(1, MAX_ID_LENGTH, "id.length")

        book.title.requireNotBlank("title")
        book.title.length.requireInRange(1, MAX_TITLE_LENGTH, "title.length")

        book.author.requireNotBlank("author")
        book.author.length.requireInRange(1, MAX_AUTHOR_LENGTH, "author.length")

        book.year.requireInRange(YEAR_RANGE.first, YEAR_RANGE.last, "year")
    }
}
