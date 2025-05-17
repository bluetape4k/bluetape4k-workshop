package io.bluetape4k.workshop.exposed.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.workshop.exposed.AbstractExposedSqlTest
import io.bluetape4k.workshop.exposed.domain.dto.MovieDTO
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.expectBodyList

class MovieControllerTest(
    @Autowired private val client: WebTestClient,
): AbstractExposedSqlTest() {

    companion object: KLoggingChannel()

    @Test
    fun `get movie by id`() {
        val id = 1

        val movie = client.httpGet("/movies/$id")
            .expectBody<MovieDTO>().returnResult().responseBody

        log.debug { "movie[$id]=$movie" }

        movie.shouldNotBeNull()
        movie.id shouldBeEqualTo id
    }

    @Test
    fun `search movies by producer name`() {
        val producerName = "Johnny"

        val movies = client.httpGet("/movies?producerName=$producerName")
            .expectBodyList<MovieDTO>().returnResult().responseBody!!

        movies shouldHaveSize 2
    }
}
