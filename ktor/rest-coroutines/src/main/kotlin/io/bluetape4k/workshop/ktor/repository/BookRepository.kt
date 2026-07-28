package io.bluetape4k.workshop.ktor.repository

import io.bluetape4k.workshop.ktor.domain.Book
import io.bluetape4k.workshop.ktor.domain.DomainError
import kotlinx.coroutines.flow.Flow

/**
 * book catalog operation 을 위한 repository abstraction 입니다.
 *
 * ## Behavior / Contract
 * - 같은 id 의 book 이 이미 있으면 [save] 는 [DomainError.Conflict] 를 throw 합니다.
 * - target id 가 없으면 [update] 는 [DomainError.NotFound] 를 throw 합니다.
 * - target id 가 없으면 [delete] 는 [DomainError.NotFound] 를 throw 합니다.
 * - [stream] 은 hot [kotlinx.coroutines.flow.SharedFlow] 를 반환합니다. subscription 이후 방출된 event 만 전달하며 replay buffer 는 없습니다.
 */
interface BookRepository {

    /** catalog 의 모든 book 을 반환합니다. */
    suspend fun findAll(): List<Book>

    /** 주어진 [id] 의 book 을 반환합니다. 존재하지 않으면 `null` 입니다. */
    suspend fun findById(id: String): Book?

    /**
     * [book] 을 persist 하고 live stream 으로 방출합니다.
     *
     * @throws DomainError.Conflict [Book.id] 를 가진 book 이 이미 있으면 발생합니다.
     */
    suspend fun save(book: Book): Book

    /**
     * [id] 위치의 book 을 [book] 으로 교체합니다.
     *
     * @throws DomainError.NotFound [id] 가 존재하지 않으면 발생합니다.
     */
    suspend fun update(id: String, book: Book): Book

    /**
     * [id] 위치의 book 을 제거합니다.
     *
     * @throws DomainError.NotFound [id] 가 존재하지 않으면 발생합니다.
     */
    suspend fun delete(id: String)

    /**
     * book 이 save 또는 update 될 때마다 방출되는 book 의 hot [Flow] 를 반환합니다.
     *
     * [kotlinx.coroutines.flow.MutableSharedFlow] 기반이며 live-only delivery 입니다. replay 는 없습니다.
     */
    fun stream(): Flow<Book>
}
