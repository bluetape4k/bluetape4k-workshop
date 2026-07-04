package io.bluetape4k.workshop.jsonview.controller

import com.fasterxml.jackson.annotation.JsonView
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.jsonview.dto.ArticleDTO
import io.bluetape4k.workshop.jsonview.dto.Views
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/articles")
class ArticleController {

    companion object: KLoggingChannel()

    private val articles = mapOf(
        1L to ArticleDTO(
            id = 1L,
            title = "Article 1",
            category = "Spring Framework",
            content = "Content 1",
            views = 1000,
            likes = 30,
        ),
        2L to ArticleDTO(
            id = 2L,
            title = "Article 2",
            category = "Kotlin",
            content = "Content 2",
            views = 5000,
            likes = 54,
        ),
    )

    @GetMapping
    @JsonView(Views.Public::class)
    fun getAllArticles(): Flow<ArticleDTO> =
        articles.values.asFlow()

    @GetMapping("/{id}")
    suspend fun getArticleDetails(@PathVariable(name = "id") id: Long): ArticleDTO? = articles[id]

    @JsonView(Views.Analytics::class)
    @GetMapping("/{id}/analytics")
    suspend fun getArticleAnalytics(@PathVariable id: Long): ArticleDTO? = articles[id]

    @JsonView(Views.Internal::class)
    @GetMapping("/{id}/internal")
    suspend fun getArticleInternal(@PathVariable id: Long): ArticleDTO? = articles[id]
}
