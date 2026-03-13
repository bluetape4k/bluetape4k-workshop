package io.bluetape4k.workshop.elasticsearch.domain.service

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.trace
import io.bluetape4k.workshop.elasticsearch.AbstractElasticsearchApplicationTest
import io.bluetape4k.workshop.elasticsearch.domain.exception.BookNotFoundException
import io.bluetape4k.workshop.elasticsearch.domain.exception.DuplicatedIsbnException
import io.bluetape4k.workshop.elasticsearch.domain.model.Book
import io.bluetape4k.workshop.elasticsearch.domain.repository.BookRepository
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldContainAll
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import kotlin.test.assertFailsWith

@Disabled("Elasticsearch Client 가 Jackson2 를 사용합니다. Spring Boot 4 는 Jackson 3를 사용해서 충돌이 발생합니다.")
class DefaultBookServiceTest(
    @param:Autowired private val service: BookService,
    @param:Autowired private val repository: BookRepository,
    @param:Autowired private val reactiveOps: ReactiveElasticsearchOperations,
): AbstractElasticsearchApplicationTest() {

    companion object: KLogging()

    @Test
    fun `context loading`() {
        service.shouldNotBeNull()
        repository.shouldNotBeNull()
        reactiveOps.shouldNotBeNull()
    }

    @Test
    fun `get all books`() = runSuspendIO {
        val books = List(3) { service.create(createBook()) }
        refreshBookIndex()
        books.forEach {
            log.debug { "saved book=$it" }
        }

        val loaded = service.getAll()
        loaded shouldHaveSize books.size
        loaded shouldBeEqualTo books
    }

    @Test
    fun `get book by isbn`() = runSuspendIO {
        val book = createBook()
        val saved = service.create(book)
        refreshBookIndex()

        val loaded = service.getByIsbn(book.isbn)!!
        loaded shouldBeEqualTo saved
    }

    @Test
    fun `find book by title and author`() = runSuspendIO {
        val saved = List(4) { service.create(createBook()) }
        refreshBookIndex()

        val loaded = service.findByTitleAndAuthor(saved[0].title, saved[0].authorName)
        loaded.forEach {
            log.trace { "loaded book=$it" }
        }
        loaded.size shouldBeGreaterOrEqualTo 1
    }

    @Test
    fun `find book by title and author contains`() = runSuspendIO {
        val books = listOf(
            Book("12 rules for life", "Jordan Peterson", 2018, "978-0345816023"),
            Book("Rules or not rules?", "Jordan Miller", 2010, "978128000000"),
            Book("Poor economy", "Jordan Miller", 2006, "9781280789000"),
            Book("The Cathedral and the Bazaar", "Eric Raymond", 1999, "9780596106386")
        )
        val saved = service.createAll(books)
        refreshBookIndex()

        val found = service.findByTitleAndAuthor("rules", "jordan")
        found.forEach {
            log.trace { "found book=$it" }
        }
        found shouldHaveSize 2
        saved shouldContainAll found
    }

    @Test
    fun `create book`() = runSuspendIO {
        val saved = service.create(createBook())
        log.trace { "saved book=$saved" }
        saved.id.shouldNotBeNull()
    }

    @Test
    fun `create book with duplicate isbnm, throw exception`() = runTest {
        val saved = service.create(createBook())
        saved.id.shouldNotBeNull()
        refreshBookIndex()

        assertFailsWith<DuplicatedIsbnException> {
            val duplicated = saved.copy(title = "New Duplicated Book", id = null)
            service.create(duplicated)
        }
    }

    @Test
    fun `delete book by id`() = runSuspendIO {
        val saved = service.create(createBook())
        saved.id.shouldNotBeNull()
        refreshBookIndex()

        service.deleteById(saved.id!!)
        refreshBookIndex()

        repository.existsById(saved.id!!).shouldBeFalse()
    }

    @Test
    fun `update existing book`() = runSuspendIO {
        val saved = service.create(createBook())
        saved.id.shouldNotBeNull()
        refreshBookIndex()

        val updatedBook = saved.copy("Updated title")
        val updated = service.update(updatedBook.id!!, updatedBook)
        refreshBookIndex()

        updated shouldBeEqualTo updatedBook
    }

    @Test
    fun `update book throws exception, if book not exists`() = runSuspendIO {
        val bookToUpdate = createBook()

        assertFailsWith<BookNotFoundException> {
            service.update("not-exists-id", bookToUpdate)
        }
    }
}
