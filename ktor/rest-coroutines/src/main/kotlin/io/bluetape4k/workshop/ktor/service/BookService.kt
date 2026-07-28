package io.bluetape4k.workshop.ktor.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.ktor.domain.Book
import io.bluetape4k.workshop.ktor.domain.DomainError
import io.bluetape4k.workshop.ktor.repository.BookRepository
import kotlinx.coroutines.flow.Flow

/**
 * book catalog 를 위한 application service 입니다.
 *
 * ## Behavior / Contract
 * - [create] 는 repository 에 위임하기 전에 bluetape4k `require*` extension 으로 모든 field 를 검증합니다.
 * - [stream] 은 non-suspend 이며 repository 의 hot [Flow] 를 반환합니다.
 */
class BookService(private val repository: BookRepository) {

    companion object : KLoggingChannel() {
        const val MAX_ID_LENGTH = 128
        const val MAX_TITLE_LENGTH = 500
        const val MAX_AUTHOR_LENGTH = 200
        val YEAR_RANGE = 1..3000
    }

    /** catalog 의 모든 book 을 반환합니다. */
    suspend fun list(): List<Book> = repository.findAll()

    /**
     * [id] 의 book 을 반환합니다. 찾지 못하면 `null` 입니다.
     *
     * @throws DomainError.NotFound book 이 존재하지 않으면 발생합니다.
     */
    suspend fun get(id: String): Book =
        repository.findById(id) ?: throw DomainError.NotFound(id)

    /**
     * 새 book 을 검증하고 생성합니다.
     *
     * @throws IllegalArgumentException 어떤 field 가 blank 이거나 너무 길거나 year 가 range 를 벗어나면 발생합니다.
     * @throws DomainError.Conflict 같은 id 의 book 이 이미 있으면 발생합니다.
     */
    suspend fun create(book: Book): Book {
        validateBook(book)
        return repository.save(book)
    }

    /**
     * 기존 book 을 검증하고 갱신합니다.
     *
     * @throws IllegalArgumentException 어떤 field 가 blank 이거나 range 를 벗어나면 발생합니다.
     * @throws DomainError.NotFound book 이 존재하지 않으면 발생합니다.
     */
    suspend fun update(id: String, book: Book): Book {
        validateBook(book)
        return repository.update(id, book)
    }

    /**
     * [id] 의 book 을 삭제합니다.
     *
     * @throws DomainError.NotFound book 이 존재하지 않으면 발생합니다.
     */
    suspend fun delete(id: String) = repository.delete(id)

    /**
     * 각 [create] 또는 [update] 때 방출되는 book 의 hot [Flow] 를 반환합니다.
     *
     * live-only 이며 늦게 구독한 subscriber 에 대한 replay 는 없습니다.
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
