package io.bluetape4k.workshop.jsonview.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jsonview.AbstractJsonViewApplicationTest
import io.bluetape4k.workshop.jsonview.dto.ArticleDTO
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

class ArticleControllerTest(
    @Autowired private val client: WebTestClient,
): AbstractJsonViewApplicationTest() {

    companion object: KLogging() {
        private const val BASE_PATH = "/articles"
    }

    fun WebTestClient.httpGet(url: String) =
        this.get().uri(url)
            .exchange()
            .expectStatus().isOk

    @Test
    fun `get all articles`() = runSuspendIO {
        val articles = client
            .httpGet(BASE_PATH)
            .returnResult<ArticleDTO>().responseBody
            .asFlow()
            .toList()

        articles shouldHaveSize 2

        articles.forEach {
            log.debug { "Article=$it" }
        }
        articles.forEach { it.views.shouldBeNull() }
        articles.forEach { it.likes.shouldBeNull() }
    }

    @Test
    fun `get article details by id`() = runSuspendIO {
        val article = client
            .httpGet("$BASE_PATH/1")
            .returnResult<ArticleDTO>().responseBody
            .awaitSingle()

        log.debug { "Article=$article" }
        article.id shouldBeEqualTo 1
        article.views shouldBeEqualTo 1000L
        article.likes shouldBeEqualTo 30L
    }

    @Test
    fun `get article for analytics`() = runSuspendIO {
        val article = client
            .httpGet("$BASE_PATH/1/analytics")
            .returnResult<ArticleDTO>().responseBody
            .awaitSingle()

        log.debug { "Article=$article" }

        article.id.shouldBeNull()
        article.title.shouldBeNull()
        article.category.shouldBeNull()

        article.views shouldBeEqualTo 1000L
        article.likes shouldBeEqualTo 30L
    }

    @Test
    fun `get articles for internal`() = runSuspendIO {
        val article = client
            .httpGet("$BASE_PATH/1/internal")
            .returnResult<ArticleDTO>().responseBody
            .awaitSingle()

        log.debug { "Article=$article" }

        article.id shouldBeEqualTo 1
        article.title.shouldNotBeNull()
        article.category.shouldNotBeNull()

        article.views shouldBeEqualTo 1000L
        article.likes shouldBeEqualTo 30L
    }
}
