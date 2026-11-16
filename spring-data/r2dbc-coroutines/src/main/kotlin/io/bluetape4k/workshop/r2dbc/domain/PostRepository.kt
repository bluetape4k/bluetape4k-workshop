package io.bluetape4k.workshop.r2dbc.domain

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.spring.r2dbc.coroutines.countAllSuspending
import io.bluetape4k.spring.r2dbc.coroutines.deleteAllSuspending
import io.bluetape4k.spring.r2dbc.coroutines.findFirstByIdOrNullSuspending
import io.bluetape4k.spring.r2dbc.coroutines.findFirstByIdSuspending
import io.bluetape4k.spring.r2dbc.coroutines.findOneByIdOrNullSuspending
import io.bluetape4k.spring.r2dbc.coroutines.findOneByIdSuspending
import io.bluetape4k.spring.r2dbc.coroutines.insertSuspending
import io.bluetape4k.spring.r2dbc.coroutines.selectAllSuspending
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
    companion object: KLoggingChannel()

    suspend fun count(): Long = operations.countAllSuspending<Post>()
    fun findAll(): Flow<Post> = operations.selectAllSuspending<Post>()
    suspend fun findOneById(id: Long): Post = operations.findOneByIdSuspending(id)
    suspend fun findOneByIdOrNull(id: Long): Post? = operations.findOneByIdOrNullSuspending(id)
    suspend fun findFirstById(id: Long): Post = operations.findFirstByIdSuspending(id)
    suspend fun findFirstByIdOrNull(id: Long): Post? = operations.findFirstByIdOrNullSuspending(id)
    suspend fun deleteAll(): Long = operations.deleteAllSuspending<Post>()
    suspend fun save(post: Post): Post = operations.insertSuspending(post)
    suspend fun init() {
        save(Post(title = "My first post title", content = "Content of my first post"))
        save(Post(title = "My second post title", content = "Content of my second post"))
    }
}
