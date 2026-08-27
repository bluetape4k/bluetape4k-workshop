package io.bluetape4k.workshop.r2dbc.domain

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.r2dbc.AbstractR2dbcApplicationTest
import kotlinx.coroutines.flow.toList
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class PostRepositoryTest(
    @param:Autowired private val postRepository: PostRepository,
): AbstractR2dbcApplicationTest() {

    companion object : KLoggingChannel()

    @Test
    fun `context loading`() {
        postRepository.shouldNotBeNull()
    }

    @Test
    fun `find all posts`() = runSuspendIO {
        val posts = postRepository.findAll().toList()
        posts.forEach { post ->
            log.debug { "post=$post" }
        }
        posts.shouldNotBeEmpty()
    }

    @Test
    fun `find one post by id`() = runSuspendIO {
        val post = postRepository.findOneById(1L)
        post.id shouldBeEqualTo 1L
        log.debug { "post=$post" }
    }

    @Test
    fun `find one post by id - not exists`() = runSuspendIO {
        postRepository.findOneByIdOrNull(-1L).shouldBeNull()
    }

    @Test
    fun `find first by id`() = runSuspendIO {
        val post = postRepository.findFirstById(1L)
        post.id shouldBeEqualTo 1L
    }

    @Test
    fun `find first by id - not exists`() = runSuspendIO {
        postRepository.findFirstByIdOrNull(-1L).shouldBeNull()
    }

    @Test
    fun `insert new post`() = runSuspendIO {
        val oldCount = postRepository.count()

        val newPost = createPost()
        val savedPost = postRepository.save(newPost)
        savedPost.id.shouldNotBeNull()

        val newCount = postRepository.count()
        newCount shouldBeEqualTo oldCount + 1
    }
}
