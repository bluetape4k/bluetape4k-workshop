package io.bluetape4k.workshop.ktor

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.ktor.domain.Book
import org.junit.jupiter.api.TestInstance

/**
 * Base class for Ktor integration tests.
 *
 * ## Behavior / Contract
 * - `@TestInstance(PER_CLASS)` is mandatory for abstract test classes per workspace convention.
 * - Provides shared [Book] fixture builders to keep test data consistent.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractKtorTest {

    companion object : KLoggingChannel()

    protected fun createBook(
        id: String = "book-1",
        title: String = "Kotlin in Action",
        author: String = "Jemerov",
        year: Int = 2017,
    ): Book = Book(id = id, title = title, author = author, year = year)

    protected fun createBooks(count: Int): List<Book> =
        (1..count).map { i ->
            Book(id = "book-$i", title = "Title $i", author = "Author $i", year = 2000 + i)
        }
}
