package io.bluetape4k.workshop.exposed.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedSqlTest
import io.bluetape4k.workshop.exposed.domain.dto.MovieDTO
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.expectBodyList

class MovieControllerTest: AbstractExposedSqlTest() {

    companion object: KLoggingChannel()

    @Test
    fun `get movie by id`() {
        val id = 1

        val movie = client
            .get()
            .uri("/movies/$id")
            .exchangeSuccessfully()
            .expectBody<MovieDTO>().returnResult().responseBody

        log.debug { "movie[$id]=$movie" }

        movie.shouldNotBeNull()
        movie.id shouldBeEqualTo id
    }

    @Test
    fun `search movies by producer name`() {
        val producerName = "Johnny"

        val movies = client
            .get()
            .uri("/movies?producerName=$producerName")
            .exchangeSuccessfully()
            .expectBodyList<MovieDTO>().returnResult().responseBody!!

        movies shouldHaveSize 2
    }
}
