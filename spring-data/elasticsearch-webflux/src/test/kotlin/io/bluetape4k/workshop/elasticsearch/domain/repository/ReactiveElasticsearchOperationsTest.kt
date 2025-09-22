package io.bluetape4k.workshop.elasticsearch.domain.repository

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.elasticsearch.AbstractElasticsearchApplicationTest
import io.bluetape4k.workshop.elasticsearch.domain.model.Book
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldContainAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.data.elasticsearch.core.query.Criteria
import org.springframework.data.elasticsearch.core.query.CriteriaQuery
import org.springframework.data.elasticsearch.core.query.Query
import org.springframework.data.elasticsearch.core.search

class ReactiveElasticsearchOperationsTest(
    @param:Autowired val reactiveOps: ReactiveElasticsearchOperations,
): AbstractElasticsearchApplicationTest() {

    companion object: KLogging()

    @Test
    fun `find all books`() = runSuspendIO {
        val saved = createRandomBooks(10)

        val query = Query.findAll()
        val loaded = reactiveOps.search<Book>(query).asFlow().map { it.content }.toList()

        loaded.forEach {
            log.debug { "loaded book=$it" }
        }
        loaded shouldContainAll saved
    }

    @Test
    fun `find by isbn`() = runSuspendIO {
        val saved = createRandomBooks(3)
        val target = saved.random()

        // Query 사용 예
//        val query = QueryBuilders.bool { bqb ->
//            bqb.must { qb ->
//                qb.match { mqb ->
//                    mqb.field("isbn").query(target.isbn)
//                }
//            }
//        }
//        val nativeQuery = NativeQuery.builder().withQuery(query).build()

        // CriteriaQuery 사용 예
        // 참고 : https://juntcom.tistory.com/149
        val criteria = Criteria.where(Book::isbn.name).`is`(target.isbn)
        val criteriaQuery = CriteriaQuery.builder(criteria).build()

        val loaded = reactiveOps.search<Book>(criteriaQuery).asFlow().map { it.content }.toList()

        loaded shouldContain target
    }

    @Test
    fun `find by title and author`() = runSuspendIO {
        val saved = createRandomBooks(3)
        val target = saved.random()

        val criteria = Criteria.where(Book::title.name).`is`(target.title)
            .and(Book::authorName.name).`is`(target.authorName)
        val query = CriteriaQuery.builder(criteria).build()

        val loaded = reactiveOps.search<Book>(query).asFlow().map { it.content }.toList()
        loaded shouldContain target
    }

    private suspend fun createRandomBooks(size: Int = 3): List<Book> {
        val books = flow { repeat(size) { emit(createBook()) } }
        return books
            .flatMapMerge {
                flow {
                    emit(reactiveOps.save(it).awaitSingle())
                }
            }
            .toList()
            .apply {
                refreshBookIndex()
            }
    }
}
