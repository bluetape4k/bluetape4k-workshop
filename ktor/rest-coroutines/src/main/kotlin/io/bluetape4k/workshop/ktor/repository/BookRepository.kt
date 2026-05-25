package io.bluetape4k.workshop.ktor.repository

import io.bluetape4k.workshop.ktor.domain.Book
import io.bluetape4k.workshop.ktor.domain.DomainError
import kotlinx.coroutines.flow.Flow

/**
 * Repository abstraction for book catalog operations.
 *
 * ## Behavior / Contract
 * - [save] throws [DomainError.Conflict] when a book with the same id already exists.
 * - [update] throws [DomainError.NotFound] when the target id does not exist.
 * - [delete] throws [DomainError.NotFound] when the target id does not exist.
 * - [stream] returns a hot [kotlinx.coroutines.flow.SharedFlow] — only events emitted after
 *   subscription are delivered. No replay buffer.
 */
interface BookRepository {

    /** Returns all books in the catalog. */
    suspend fun findAll(): List<Book>

    /** Returns the book with the given [id], or `null` if it does not exist. */
    suspend fun findById(id: String): Book?

    /**
     * Persists [book] and emits it to the live stream.
     *
     * @throws DomainError.Conflict if a book with [Book.id] already exists.
     */
    suspend fun save(book: Book): Book

    /**
     * Replaces the book at [id] with [book].
     *
     * @throws DomainError.NotFound if [id] does not exist.
     */
    suspend fun update(id: String, book: Book): Book

    /**
     * Removes the book at [id].
     *
     * @throws DomainError.NotFound if [id] does not exist.
     */
    suspend fun delete(id: String)

    /**
     * Returns a hot [Flow] of books emitted whenever a book is saved or updated.
     *
     * Backed by a [kotlinx.coroutines.flow.MutableSharedFlow] — live-only delivery, no replay.
     */
    fun stream(): Flow<Book>
}
