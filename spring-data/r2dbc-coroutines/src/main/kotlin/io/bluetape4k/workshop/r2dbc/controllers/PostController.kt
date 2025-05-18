package io.bluetape4k.workshop.r2dbc.controllers

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.r2dbc.domain.Comment
import io.bluetape4k.workshop.r2dbc.domain.CommentRepository
import io.bluetape4k.workshop.r2dbc.domain.Post
import io.bluetape4k.workshop.r2dbc.domain.PostRepository
import io.bluetape4k.workshop.r2dbc.exception.PostNotFoundException
import kotlinx.coroutines.flow.Flow
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/posts")
class PostController(
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
) {
    companion object: KLoggingChannel()

    @GetMapping
    fun findAll(): Flow<Post> = postRepository.findAll()

    @GetMapping("/{id}")
    suspend fun findOne(@PathVariable id: Long): Post? =
        postRepository.findOneByIdOrNull(id) ?: throw PostNotFoundException(id)

    @PostMapping
    suspend fun save(@RequestBody post: Post): Post {
        return postRepository.save(post)
    }

    @GetMapping("/{postId}/comments")
    fun findCommentsByPostId(@PathVariable postId: Long): Flow<Comment> =
        commentRepository.findAllByPostId(postId)

    @GetMapping("/{postId}/comments/count")
    suspend fun countCommentsByPostId(@PathVariable postId: Long): Long =
        commentRepository.countByPostId(postId)

    @PostMapping("/{postId}/comments")
    suspend fun saveComment(@PathVariable postId: Long, @RequestBody comment: Comment) {
        commentRepository.save(comment.copy(postId = postId, content = comment.content))
    }
}
