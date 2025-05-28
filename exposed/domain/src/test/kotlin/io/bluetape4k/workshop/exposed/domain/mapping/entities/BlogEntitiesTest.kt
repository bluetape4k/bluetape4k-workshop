package io.bluetape4k.workshop.exposed.domain.mapping.entities

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.exists
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
     * INSERT INTO posts (title) VALUES ('Post 1');
     *
     * INSERT INTO post_details (id, created_on, created_by)
     * VALUES (1, '2025-02-06', 'admin');
     * ```
     *
     * ```sql
     * SELECT posts.id, posts.title
     *   FROM posts
     *  WHERE posts.id = 1;
     *
     * -- lazy loading for PostDetails of Post
     * SELECT post_details.id,
     *        post_details.created_on,
     *        post_details.created_by
     *   FROM post_details
     *  WHERE post_details.id = 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create post by DAO`(testDB: TestDB) {
        withTables(testDB, *blogTables) {

            val post = Post.new { title = "Post 1" }
            log.debug { "Post=$post" }

            // one-to-one 관계에서 ownership 을 가진 Post의 id 값을 지정합니다.
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
