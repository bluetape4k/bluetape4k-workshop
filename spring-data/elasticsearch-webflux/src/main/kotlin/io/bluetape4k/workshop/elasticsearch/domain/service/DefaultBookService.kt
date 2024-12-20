package io.bluetape4k.workshop.elasticsearch.domain.service

import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.elasticsearch.domain.exception.BookNotFoundException
import io.bluetape4k.workshop.elasticsearch.domain.exception.DuplicatedIsbnException
import io.bluetape4k.workshop.elasticsearch.domain.model.Book
import io.bluetape4k.workshop.elasticsearch.domain.repository.BookRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.data.elasticsearch.client.elc.NativeQuery
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchTemplate
import org.springframework.stereotype.Service

@Service
class DefaultBookService(
    private val bookRepository: BookRepository,
    private val reactiveTemplate: ReactiveElasticsearchTemplate,
): BookService {

    companion object: KLogging()

    override suspend fun getByIsbn(isbn: String): Book? {
        return bookRepository.findByIsbn(isbn)
    }

    override suspend fun getAll(): List<Book> {
        return bookRepository.findAll().toList()
    }

    override suspend fun findByAuthor(authorName: String): List<Book> {
        authorName.requireNotBlank("authorName")
        return bookRepository.findByAuthorName(authorName).toList()
    }

    override suspend fun findByTitleAndAuthor(title: String, author: String): List<Book> {
        val criteria = QueryBuilders.bool { bqb ->
            bqb.must { qb ->
                qb.match { mqb ->
                    mqb.field("authorName").query(author)
                }
                qb.match { mqb ->
                    mqb.field("title").query(title)
                }
            }
        }
        val query = NativeQuery.builder().withQuery(criteria).build()
        return reactiveTemplate.search(query, Book::class.java).map { it.content }.asFlow().toList()
    }

    override suspend fun create(book: Book): Book {
        if (getByIsbn(book.isbn) != null) {
            throw DuplicatedIsbnException("Book with isbn[${book.isbn}] already exists. Use update instead!")
        }
        log.debug { "Create new book. $book" }
        return bookRepository.save(book)
    }

    override suspend fun createAll(books: Collection<Book>): Collection<Book> {
        return books.map { create(it) }
    }

    override suspend fun deleteById(id: String) {
        id.requireNotBlank("id")

        // 굳이 이렇게 할 필요는 없다. Controller의 예외 처리 예를 보여주기 위해 이렇게 했다.
        if (bookRepository.existsById(id)) {
            bookRepository.deleteById(id)
        } else {
            throw BookNotFoundException("There is not book associate with the given id[$id]")
        }
    }

    override suspend fun update(id: String, book: Book): Book {
        var oldBook = bookRepository.findById(id)
            ?: throw BookNotFoundException("There is not book associate with the given id[$id]")

        oldBook = book.copy(id = oldBook.id)
        return bookRepository.save(oldBook)
    }
}
