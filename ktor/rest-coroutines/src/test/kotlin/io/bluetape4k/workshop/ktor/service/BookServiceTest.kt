package io.bluetape4k.workshop.ktor.service

import io.bluetape4k.workshop.ktor.AbstractKtorTest
import io.bluetape4k.workshop.ktor.domain.Book
import io.bluetape4k.workshop.ktor.domain.DomainError
import io.bluetape4k.workshop.ktor.repository.InMemoryBookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith

class BookServiceTest : AbstractKtorTest() {

    private fun service(repository: InMemoryBookRepository = InMemoryBookRepository()) =
        BookService(repository)

    private fun validBook(
        id: String = "v-1",
        title: String = "Valid Title",
        author: String = "Valid Author",
        year: Int = 2020,
    ) = Book(id = id, title = title, author = author, year = year)

    // ──────────────────────────────────────────────────────────────────────
    // 정상 경로
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `create returns saved book for valid input`() = runTest {
        val svc = service()
        val book = svc.create(validBook())
        book.id shouldBeEqualTo "v-1"
    }

    @Test
    fun `get returns saved book`() = runTest {
        val svc = service()
        svc.create(validBook(id = "get-1"))
        val found = svc.get("get-1")
        found.id shouldBeEqualTo "get-1"
    }

    @Test
    fun `get throws DomainError NotFound for unknown id`() = runTest {
        assertFailsWith<DomainError.NotFound> {
            service().get("no-such")
        }
    }

    @Test
    fun `create throws DomainError Conflict for duplicate id`() = runTest {
        val svc = service()
        svc.create(validBook(id = "dup"))
        assertFailsWith<DomainError.Conflict> {
            svc.create(validBook(id = "dup"))
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // id 검증
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `create throws IllegalArgumentException when id is blank`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service().create(validBook(id = ""))
        }
    }

    @Test
    fun `create throws IllegalArgumentException when id exceeds max length`() = runTest {
        val longId = "x".repeat(BookService.MAX_ID_LENGTH + 1)
        assertFailsWith<IllegalArgumentException> {
            service().create(validBook(id = longId))
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // title 검증
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `create throws IllegalArgumentException when title is blank`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service().create(validBook(title = ""))
        }
    }

    @Test
    fun `create throws IllegalArgumentException when title exceeds max length`() = runTest {
        val longTitle = "t".repeat(BookService.MAX_TITLE_LENGTH + 1)
        assertFailsWith<IllegalArgumentException> {
            service().create(validBook(title = longTitle))
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // author 검증
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `create throws IllegalArgumentException when author is blank`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service().create(validBook(author = ""))
        }
    }

    @Test
    fun `create throws IllegalArgumentException when author exceeds max length`() = runTest {
        val longAuthor = "a".repeat(BookService.MAX_AUTHOR_LENGTH + 1)
        assertFailsWith<IllegalArgumentException> {
            service().create(validBook(author = longAuthor))
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // year 검증
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `create throws IllegalArgumentException when year is below range`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service().create(validBook(year = BookService.YEAR_RANGE.first - 1))
        }
    }

    @Test
    fun `create throws IllegalArgumentException when year is above range`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service().create(validBook(year = BookService.YEAR_RANGE.last + 1))
        }
    }

    @Test
    fun `create accepts year at lower boundary`() = runTest {
        val book = service().create(validBook(id = "y-low", year = BookService.YEAR_RANGE.first))
        book.year shouldBeEqualTo BookService.YEAR_RANGE.first
    }

    @Test
    fun `create accepts year at upper boundary`() = runTest {
        val book = service().create(validBook(id = "y-high", year = BookService.YEAR_RANGE.last))
        book.year shouldBeEqualTo BookService.YEAR_RANGE.last
    }

    // ──────────────────────────────────────────────────────────────────────
    // delete + update 검증
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `delete removes book from list`() = runTest {
        val svc = service()
        svc.create(validBook(id = "del-1"))
        svc.delete("del-1")
        svc.list().size shouldBeEqualTo 0
    }

    @Test
    fun `update replaces book content`() = runTest {
        val svc = service()
        svc.create(validBook(id = "upd-1"))
        val updated = svc.update("upd-1", validBook(id = "upd-1", title = "New Title"))
        updated.title shouldBeEqualTo "New Title"
    }
}
