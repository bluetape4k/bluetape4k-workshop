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
 * [BookRepository] 의 thread-safe in-memory 구현입니다.
 *
 * ## Behavior / Contract
 * - O(1) read/write 를 위해 [ConcurrentHashMap] 을 기반으로 합니다.
 * - SSE stream 은 `extraBufferCapacity = 64` 와 `onBufferOverflow = BufferOverflow.SUSPEND` 를 가진 [MutableSharedFlow] 를 기반으로 합니다.
 * - stalled SSE subscriber 가 HTTP handler 를 무기한 block 하지 못하도록 [save] 와 [update] 는 5초 timeout 으로 `sharedFlow.emit(book)` 을 시도합니다. emit 성공 여부와 관계없이 book 은 항상 persist 됩니다.
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
