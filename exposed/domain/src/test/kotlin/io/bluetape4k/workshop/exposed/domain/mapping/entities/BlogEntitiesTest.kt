package io.bluetape4k.workshop.exposed.domain.mapping.entities

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withDb
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.exists
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDate

class BlogEntitiesTest: AbstractExposedTest() {

    companion object: KLogging()

    private val blogTables = arrayOf(
        PostTable, PostDetailsTable, PostCommentTable, PostTagTable, TagTable
    )

    private val today = LocalDate.now()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create blog entities`(testDB: TestDB) {
        withDb(testDB) {
            SchemaUtils.create(*blogTables)

            try {
                blogTables.all { it.exists() }.shouldBeTrue()
            } finally {
                SchemaUtils.drop(*blogTables)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create post by DAO`(testDB: TestDB) {
        withTables(testDB, *blogTables) {

            val post = Post.new {
                title = "Post 1"
            }
            log.debug { "Post=$post" }

            val postDetails = PostDetails.new(post.id.value) {
                createdOn = today
                createdBy = "admin"
            }
            log.debug { "PostDetails=$postDetails" }

            val loadedPost = Post.findById(post.id)!!

            loadedPost.title shouldBeEqualTo "Post 1"
            loadedPost.details.createdOn shouldBeEqualTo today
            loadedPost.details.createdBy shouldBeEqualTo "admin"
        }
    }
}
