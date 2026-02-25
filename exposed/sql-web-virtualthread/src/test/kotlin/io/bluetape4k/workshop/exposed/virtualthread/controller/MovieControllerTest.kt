package io.bluetape4k.workshop.exposed.virtualthread.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.virtualthread.AbstractExposedTest
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieDTO
import io.bluetape4k.workshop.shared.web.httpGet
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBodyList
import org.springframework.test.web.reactive.server.returnResult

class MovieControllerTest: AbstractExposedTest() {

    companion object: KLoggingChannel()

    @Test
    fun `get movie by id`() = runSuspendIO {
        val id = 1

        val movie = client
            .httpGet("/movies/$id")
            .expectStatus().is2xxSuccessful
            .returnResult<MovieDTO>().responseBody
            .awaitSingle()

        log.debug { "movie[$id]=$movie" }

        movie.shouldNotBeNull()
        movie.id shouldBeEqualTo id
    }

    @Test
    fun `search movies by producer name`() = runSuspendIO {
        val producerName = "Johnny"

        val movies = client
            .httpGet("/movies?producerName=$producerName")
            .expectStatus().is2xxSuccessful
            .expectBodyList<MovieDTO>()
            .returnResult().responseBody

        movies.shouldNotBeNull() shouldHaveSize 2
    }
}
