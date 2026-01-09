package io.bluetape4k.workshop.exposed.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedSqlTest
import io.bluetape4k.workshop.exposed.domain.dto.MovieActorCountDTO
import io.bluetape4k.workshop.exposed.domain.dto.MovieWithActorDTO
import io.bluetape4k.workshop.exposed.domain.dto.MovieWithProducingActorDTO
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.expectBodyList

class MovieActorsControllerTest: AbstractExposedSqlTest() {

    companion object: KLoggingChannel()

    @Test
    fun `get movie with actors`() {
        val movieId = 1

        val movieWithActors = client
            .get()
            .uri("/movie-actors/$movieId")
            .exchangeSuccessfully()
            .expectBody<MovieWithActorDTO>().returnResult().responseBody

        log.debug { "movieWithActors[$movieId]=$movieWithActors" }

        movieWithActors.shouldNotBeNull()
        movieWithActors.id shouldBeEqualTo movieId
    }

    @Test
    fun `get movie and actor count group by movie name`() {

        val movieActorCounts = client
            .get()
            .uri("/movie-actors/count")
            .exchangeSuccessfully()
            .expectBodyList<MovieActorCountDTO>().returnResult().responseBody!!

        movieActorCounts.shouldNotBeEmpty()

        movieActorCounts.forEach {
            log.debug { "movieActorCount=$it" }
        }
    }

    @Test
    fun `get movie and acting producer`() {
        val movieWithProducers = client
            .get()
            .uri("/movie-actors/acting-producers")
            .exchangeSuccessfully()
            .expectBodyList<MovieWithProducingActorDTO>().returnResult().responseBody!!

        movieWithProducers.forEach {
            log.debug { "movieWithProducer=$it" }
        }
        movieWithProducers.shouldNotBeEmpty()
    }
}
