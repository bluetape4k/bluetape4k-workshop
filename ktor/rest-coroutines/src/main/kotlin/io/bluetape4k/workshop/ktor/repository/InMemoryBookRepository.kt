package io.bluetape4k.workshop.ktor.repository

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.ktor.domain.Book
import io.bluetape4k.workshop.ktor.domain.DomainError
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of [BookRepository].
 *
 * ## Behavior / Contract
 * - Backed by a [ConcurrentHashMap] for O(1) reads/writes.
 * - SSE stream backed by a [MutableSharedFlow] with `extraBufferCapacity = 64`
 *   and `onBufferOverflow = BufferOverflow.SUSPEND`.
 * - [save] and [update] attempt `sharedFlow.emit(book)` with a 5-second timeout so
 *   a stalled SSE subscriber cannot block the HTTP handler indefinitely.
 *   The book is always persisted regardless of whether the emit succeeds.
 */
class InMemoryBookRepository : BookRepository {

    companion object : KLoggingChannel() {
        private const val EMIT_TIMEOUT_MS = 5_000L
    }

    private val store = ConcurrentHashMap<String, Book>()
    private val sharedFlow = MutableSharedFlow<Book>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    override suspend fun findAll(): List<Book> = store.values.toList()

    override suspend fun findById(id: String): Book? {
        id.requireNotBlank("id")
        return store[id]
    }

    override suspend fun save(book: Book): Book {
        if (store.putIfAbsent(book.id, book) != null) {
            throw DomainError.Conflict("Book already exists: id=${book.id}")
        }
        emitWithTimeout(book)
        return book
    }

    override suspend fun update(id: String, book: Book): Book {
        id.requireNotBlank("id")
        store[id] ?: throw DomainError.NotFound(id)
        val updated = book.copy(id = id)
        store[id] = updated
        emitWithTimeout(updated)
        return updated
    }

    override suspend fun delete(id: String) {
        id.requireNotBlank("id")
        store.remove(id) ?: throw DomainError.NotFound(id)
    }

    override fun stream(): Flow<Book> = sharedFlow.asSharedFlow()

    private suspend fun emitWithTimeout(book: Book) {
        val emitted = withTimeoutOrNull(EMIT_TIMEOUT_MS) { sharedFlow.emit(book) } != null
        if (!emitted) {
            log.warn { "SSE emit timed out for book ${book.id}; book saved, event dropped" }
        }
    }
}
