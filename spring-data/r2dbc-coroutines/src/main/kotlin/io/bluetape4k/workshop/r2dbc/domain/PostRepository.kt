package io.bluetape4k.workshop.r2dbc.domain

import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.r2dbc.coroutines.coCountAll
import io.bluetape4k.spring.r2dbc.coroutines.coDeleteAll
import io.bluetape4k.spring.r2dbc.coroutines.coFindFirstById
import io.bluetape4k.spring.r2dbc.coroutines.coFindFirstByIdOrNull
import io.bluetape4k.spring.r2dbc.coroutines.coFindOneById
import io.bluetape4k.spring.r2dbc.coroutines.coFindOneByIdOrNull
import io.bluetape4k.spring.r2dbc.coroutines.coInsert
import io.bluetape4k.spring.r2dbc.coroutines.coSelectAll
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter
import org.springframework.data.r2dbc.core.R2dbcEntityOperations
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

@Repository
class PostRepository(
    private val client: DatabaseClient,
    private val operations: R2dbcEntityOperations,
    private val mappingR2dbcConverter: MappingR2dbcConverter,
) {
    companion object: KLogging()

    suspend fun count(): Long {
        return operations.coCountAll<Post>()
    }

    fun findAll(): Flow<Post> {
        return operations.coSelectAll()
    }

    suspend fun findOneById(id: Long): Post {
        return operations.coFindOneById(id)
    }

    suspend fun findOneByIdOrNull(id: Long): Post? {
        return operations.coFindOneByIdOrNull(id)
    }

    suspend fun findFirstById(id: Long): Post {
        return operations.coFindFirstById(id)
    }

    suspend fun findFirstByIdOrNull(id: Long): Post? {
        return operations.coFindFirstByIdOrNull(id)
    }

    suspend fun deleteAll(): Long {
        return operations.coDeleteAll<Post>()
    }

    suspend fun save(post: Post): Post {
        return operations.coInsert(post)
    }

    suspend fun init() {
        save(Post(title = "My first post title", content = "Content of my first post"))
        save(Post(title = "My second post title", content = "Content of my second post"))
    }
}
