package io.bluetape4k.workshop.elasticsearch.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.trace
import io.bluetape4k.spring.tests.httpDelete
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.spring.tests.httpPost
import io.bluetape4k.spring.tests.httpPut
import io.bluetape4k.workshop.elasticsearch.AbstractElasticsearchApplicationTest
import io.bluetape4k.workshop.elasticsearch.domain.dto.toBook
import io.bluetape4k.workshop.elasticsearch.domain.dto.toModifyBookRequest
import io.bluetape4k.workshop.elasticsearch.domain.model.Book
import io.bluetape4k.workshop.elasticsearch.domain.service.BookService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

class BookControllerTest(
    @param:Autowired private val client: WebTestClient,
    @param:Autowired private val bookService: BookService,
): AbstractElasticsearchApplicationTest() {

    companion object: KLogging() {
        private const val BOOK_PATH = "/v1/books"
    }

    @Test
    fun `context loading`() {
        client.shouldNotBeNull()
        bookService.shouldNotBeNull()
    }

    @Test
    fun `get all books`() = runSuspendIO {
        val saved = insertRandomBooks(5)

        val loaded = client
            .httpGet(BOOK_PATH)
            .returnResult<Book>().responseBody
            .asFlow().toList()

        loaded.forEach {
            log.trace { "loaded book=$it" }
        }
        loaded shouldHaveSize saved.size
    }

    @Test
    fun `find book by isbn`() = runSuspendIO {
        val saved = insertRandomBooks(3)

        val foundBook = client
            .httpGet("$BOOK_PATH/${saved.last().isbn}")
            .returnResult<Book>().responseBody
            .awaitSingle()

        foundBook.shouldNotBeNull()
        foundBook.isbn shouldBeEqualTo saved.last().isbn
    }

    @Test
    fun `find book with not exists isbn`() = runTest {
        insertRandomBooks(3)

        client.httpGet("$BOOK_PATH/not-exists", HttpStatus.NOT_FOUND)
    }

    @Test
    fun `find book by author and title`() = runSuspendIO {
        val saved = insertRandomBooks(3)
        val last = saved.last()
        val title = last.title
        val author = last.authorName

        val foundBooks = client.httpGet("$BOOK_PATH?title=$title&author=$author")
            .returnResult<Book>().responseBody
            .asFlow().toList()

        foundBooks.forEach {
            log.trace { "found book=$it" }
        }
        foundBooks.size shouldBeGreaterOrEqualTo 1
    }

    @Test
    fun `create book`() = runSuspendIO {
        val book = createBook()

        val createdBook = client
            .httpPost(BOOK_PATH, book.toModifyBookRequest(), HttpStatus.CREATED)
            .returnResult<Book>().responseBody
            .awaitSingle()

        createdBook.id.shouldNotBeNull()
        createdBook shouldBeEqualTo book.copy(id = createdBook.id)
    }

    @Test
    fun `update existing book`() = runSuspendIO {
        val saved = insertRandomBooks(3)
        val last = saved.last()

        val updateRequest = last.toModifyBookRequest().copy(title = "updated title")

        val updatedBook = client
            .httpPut("$BOOK_PATH/${last.id}", updateRequest)
            .returnResult<Book>().responseBody
            .awaitSingle()

        updatedBook shouldBeEqualTo updateRequest.toBook().copy(id = last.id)
    }

    @Test
    fun `update not existing book`() = runSuspendIO {
        val saved = insertRandomBooks(3)
        val last = saved.last()

        val updateRequest = last.toModifyBookRequest().copy(title = "updated title")

        client.httpPut("$BOOK_PATH/not-exisis", updateRequest, HttpStatus.NOT_FOUND)
    }

    @Test
    fun `delete book by id`() = runSuspendIO {
        val saved = insertRandomBooks(3)
        val last = saved.last()

        client.httpDelete("$BOOK_PATH/${last.id}")
    }

    @Test
    fun `delete book by invalid id`() = runSuspendIO {
        insertRandomBooks(3)
        client.httpDelete("$BOOK_PATH/not-exists", HttpStatus.NOT_FOUND)
    }

    private suspend fun insertRandomBooks(size: Int = 10): List<Book> {
        return bookService.createAll(List(size) { createBook() }).toList()
            .apply {
                // NOTE: indexOps refresh 해줘야 검색이 됩니다.
                refreshBookIndex()
            }
    }
}
