package io.bluetape4k.workshop.elasticsearch.domain.repository

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.elasticsearch.AbstractElasticsearchApplicationTest
import io.bluetape4k.workshop.elasticsearch.domain.model.Book
import org.amshove.kluent.shouldHaveSize
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.core.query.Query
import org.springframework.data.elasticsearch.core.search

class ElasticsearchOperationsTest: AbstractElasticsearchApplicationTest() {

    companion object: KLogging()

    @Test
    fun `search all books`() {
        val books = List(10) { createBook() }

        val saved = operations.save(books)
        indexOpsForBook.refresh()

        saved.forEach {
            log.debug { "saved book=$it" }
        }

        val query = Query.findAll()
        val loaded = operations.search<Book>(query).map { it.content }.toList()

        loaded shouldHaveSize books.size
    }
}
