package io.bluetape4k.workshop.exposed.virtualthread.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.workshop.exposed.virtualthread.AbstractExposedTest
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieDTO
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

class MovieControllerTest(
    @Autowired private val client: WebTestClient,
): AbstractExposedTest() {

    companion object: KLoggingChannel()

    @Test
    fun `get movie by id`() = runSuspendIO {
        val id = 1

        val movie = client
            .httpGet("/movies/$id")
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
            .returnResult<MovieDTO>().responseBody
            .asFlow().toList()

        movies shouldHaveSize 2
    }
}
