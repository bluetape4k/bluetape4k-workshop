package io.bluetape4k.workshop.exposed.domain.mapping.entities

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.jetbrains.exposed.dao.entityCache
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

    /**
     * ```sql
     * INSERT INTO POSTS (TITLE) VALUES ('Post 1');
     * INSERT INTO POST_DETAILS (ID, CREATED_ON, CREATED_BY) VALUES (1, '2025-01-24', 'admin')
     * ```
     *
     * ```sql
     * SELECT POSTS.ID,
     *        POSTS.TITLE
     *   FROM POSTS
     *  WHERE POSTS.ID = 1;
     *
     * SELECT POST_DETAILS.ID,
     *        POST_DETAILS.CREATED_ON,
     *        POST_DETAILS.CREATED_BY
     *   FROM POST_DETAILS
     *  WHERE POST_DETAILS.ID = 1;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create post by DAO`(testDB: TestDB) {
        withTables(testDB, *blogTables) {

            val post = Post.new { title = "Post 1" }
            log.debug { "Post=$post" }

            val postDetails = PostDetails.new(post.id.value) {
                createdOn = LocalDate.now()
                createdBy = "admin"
            }
            log.debug { "PostDetails=$postDetails" }

            entityCache.clear()

            val loadedPost = Post.findById(post.id)!!

            loadedPost shouldBeEqualTo post
            loadedPost.details shouldBeEqualTo postDetails
        }
    }
}
