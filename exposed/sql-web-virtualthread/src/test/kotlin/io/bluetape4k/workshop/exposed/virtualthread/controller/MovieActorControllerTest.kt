package io.bluetape4k.workshop.exposed.virtualthread.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.workshop.exposed.virtualthread.AbstractExposedTest
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieActorCountDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieWithActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieWithProducingActorDTO
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

class MovieActorControllerTest(
    @Autowired private val client: WebTestClient,
): AbstractExposedTest() {

    companion object: KLoggingChannel()

    @Test
    fun `get movie with actors`() = runSuspendIO {
        val movieId = 1

        val movieWithActors = client
            .httpGet("/movie-actors/$movieId")
            .returnResult<MovieWithActorDTO>().responseBody
            .awaitSingle()

        log.debug { "movieWithActors[$movieId]=$movieWithActors" }

        movieWithActors.shouldNotBeNull()
        movieWithActors.id shouldBeEqualTo movieId
    }

    @Test
    fun `get movie and actor count group by movie name`() = runSuspendIO {

        val movieActorCounts = client
            .httpGet("/movie-actors/count")
            .returnResult<MovieActorCountDTO>().responseBody
            .asFlow()
            .toList()

        movieActorCounts.shouldNotBeEmpty()

        movieActorCounts.forEach {
            log.debug { "movieActorCount=$it" }
        }
    }

    @Test
    fun `get movie and acting producer`() = runSuspendIO {
        val movieWithProducers = client
            .httpGet("/movie-actors/acting-producers")
            .returnResult<MovieWithProducingActorDTO>().responseBody
            .asFlow()
            .toList()

        movieWithProducers.forEach {
            log.debug { "movieWithProducer=$it" }
        }
        movieWithProducers.shouldNotBeEmpty()
    }
}
