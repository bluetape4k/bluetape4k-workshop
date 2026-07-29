package io.bluetape4k.workshop.ktor

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.ktor.domain.Book
import org.junit.jupiter.api.TestInstance

/**
 * Ktor integration test 의 base class 입니다.
 *
 * ## Behavior / Contract
 * - workspace convention 에 따라 abstract test class 에는 `@TestInstance(PER_CLASS)` 가 필수입니다.
 * - test data 를 일관되게 유지하기 위해 shared [Book] fixture builder 를 제공합니다.
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
