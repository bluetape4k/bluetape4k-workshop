package io.bluetape4k.workshop.exposed.virtualthread.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.virtualthread.AbstractExposedTest
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieActorCountDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieWithActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieWithProducingActorDTO
import io.bluetape4k.workshop.shared.webflux.httpGet
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.expectBodyList

class MovieActorControllerTest(@Autowired private val client: WebTestClient): AbstractExposedTest() {

    companion object: KLogging()

    @Test
    fun `get movie with actors`() {
        val movieId = 1

        val movieWithActors = client.httpGet("/movie-actors/$movieId")
            .expectBody<MovieWithActorDTO>().returnResult().responseBody

        log.debug { "movieWithActors[$movieId]=$movieWithActors" }

        movieWithActors.shouldNotBeNull()
        movieWithActors.id shouldBeEqualTo movieId
    }

    @Test
    fun `get movie and actor count group by movie name`() {

        val movieActorCounts = client.httpGet("/movie-actors/count")
            .expectBodyList<MovieActorCountDTO>().returnResult().responseBody!!

        movieActorCounts.shouldNotBeEmpty()

        movieActorCounts.forEach {
            log.debug { "movieActorCount=$it" }
        }
    }

    @Test
    fun `get movie and acting producer`() {
        val movieWithProducers = client.httpGet("/movie-actors/acting-producers")
            .expectBodyList<MovieWithProducingActorDTO>().returnResult().responseBody!!

        movieWithProducers.forEach {
            log.debug { "movieWithProducer=$it" }
        }
        movieWithProducers.shouldNotBeEmpty()
    }
}
