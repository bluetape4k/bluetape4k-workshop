package io.bluetape4k.workshop.elasticsearch.domain.repository

import io.bluetape4k.workshop.elasticsearch.domain.model.Book
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.kotlin.CoroutineSortingRepository

interface BookRepository: CoroutineCrudRepository<Book, String>, CoroutineSortingRepository<Book, String> {

    fun findByAuthorName(authorName: String): Flow<Book>

    fun findByTitleAndAuthorName(title: String, authorName: String): Flow<Book>

    suspend fun findByIsbn(isbn: String): Book?
}
