package io.bluetape4k.workshop.exposed.virtualthread.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieActorCountDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieWithActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieWithProducingActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.repository.MovieRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 영화와 출연 배우 정보를 제공하는 컨트롤러
 */
@RestController
@RequestMapping("/movie-actors")
class MovieActorsController(private val movieRepo: MovieRepository) {

    companion object: KLogging()

    @GetMapping("/{movieId}")
    fun getMovieWithActors(@PathVariable movieId: Int): MovieWithActorDTO? {
        return movieRepo.getMovieWithActors(movieId).await()
    }

    @GetMapping("/count")
    fun getMovieActorsCount(): List<MovieActorCountDTO> {
        return movieRepo.getMovieActorsCount().await()
    }


    @GetMapping("/acting-producers")
    fun findMoviesWithActingProducers(): List<MovieWithProducingActorDTO> {
        return movieRepo.findMoviesWithActingProducers().await()
    }
}
