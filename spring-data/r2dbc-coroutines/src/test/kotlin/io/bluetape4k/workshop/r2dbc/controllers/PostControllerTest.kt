package io.bluetape4k.workshop.r2dbc.controllers

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.spring.tests.httpPost
import io.bluetape4k.workshop.r2dbc.AbstractR2dbcApplicationTest
import io.bluetape4k.workshop.r2dbc.domain.Post
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

class PostControllerTest(
    @Autowired private val client: WebTestClient,
): AbstractR2dbcApplicationTest() {

    companion object: KLoggingChannel()

    @Test
    fun `find all posts`() = runSuspendIO {
        val posts = client
            .httpGet("/posts")
            .returnResult<Post>().responseBody
            .asFlow()
            .toList()

        posts.shouldNotBeEmpty()
        posts.forEach { post ->
            log.debug { "post=$post" }
        }
    }

    @Test
    fun `find one post by id`() = runSuspendIO {
        val post = client
            .httpGet("/posts/1")
            .returnResult<Post>().responseBody
            .awaitSingle()

        log.debug { "Post[1]=$post" }
        post.id shouldBeEqualTo 1
    }

    @Test
    fun `find one post by non-existing id`() = runSuspendIO {
        client
            .httpGet("/posts/9999", HttpStatus.NOT_FOUND)
            .returnResult<Post>().responseBody
            .awaitFirstOrNull()
    }

    @Test
    fun `save new post`() = runSuspendIO {
        val newPost = createPost()

        val savedPost = client
            .httpPost("/posts", newPost)
            .returnResult<Post>().responseBody
            .awaitSingle()

        savedPost.id.shouldNotBeNull()
        savedPost shouldBeEqualTo newPost.copy(id = savedPost.id)
    }

    @Test
    fun `count of comments by post id`() = runSuspendIO {
        val commentCount1 = countOfCommentByPostId(1L)
        val commentCount2 = countOfCommentByPostId(2L)

        commentCount1 shouldBeGreaterOrEqualTo 0
        commentCount2 shouldBeGreaterOrEqualTo 0
    }

    @Test
    fun `count of comments by non-existing post id`() = runSuspendIO {
        countOfCommentByPostId(9999L) shouldBeEqualTo 0L
    }

    private suspend fun countOfCommentByPostId(postId: Long): Long {
        return client
            .httpGet("/posts/$postId/comments/count")
            .returnResult<Long>().responseBody
            .awaitSingle()
    }
}
