package io.bluetape4k.workshop.exposed.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.exposed.domain.dto.MovieActorCountDTO
import io.bluetape4k.workshop.exposed.domain.dto.MovieWithActorDTO
import io.bluetape4k.workshop.exposed.domain.dto.MovieWithProducingActorDTO
import io.bluetape4k.workshop.exposed.domain.respository.MovieRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 영화 배우 정보를 제공하는 컨트롤러
 */
@RestController
@RequestMapping("/movie-actors")
class MovieActorsController(
    private val movieRepo: MovieRepository,
): CoroutineScope by CoroutineScope(Dispatchers.IO + SupervisorJob()) {

    companion object: KLoggingChannel()

    @GetMapping("/{movieId}")
    suspend fun getMovieWithActors(@PathVariable movieId: Int): MovieWithActorDTO? {
        return movieRepo.getMovieWithActors(movieId)
    }

    @GetMapping("/count")
    suspend fun getMovieActorsCount(): List<MovieActorCountDTO> {
        return movieRepo.getMovieActorsCount()
    }


    @GetMapping("/acting-producers")
    suspend fun findMoviesWithActingProducers(): List<MovieWithProducingActorDTO> {
        return movieRepo.findMoviesWithActingProducers()
    }
}
