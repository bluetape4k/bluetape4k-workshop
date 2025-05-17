package io.bluetape4k.workshop.exposed.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.dto.MovieDTO
import io.bluetape4k.workshop.exposed.domain.respository.MovieRepository
import io.bluetape4k.workshop.exposed.domain.schema.Movies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 영화[Movies] 정보를 제공하는 Controller
 */
@RestController
@RequestMapping("/movies")
class MovieController(
    private val movieRepo: MovieRepository,
): CoroutineScope by CoroutineScope(Dispatchers.IO + SupervisorJob()) {

    companion object: KLoggingChannel()

    @GetMapping("/{id}")
    suspend fun getMovieById(@PathVariable("id") movieId: Int): MovieDTO? {
        return movieRepo.findById(movieId)
    }

    @GetMapping
    suspend fun searchMovies(request: ServerHttpRequest): List<MovieDTO> {
        val params = request.queryParams.map { it.key to it.value.first() }.toMap()
        log.debug { "Search Movies... params=$params" }

        return movieRepo.searchMovie(params)
    }

    @PostMapping
    suspend fun createMovie(@RequestBody movieDTO: MovieDTO): MovieDTO? {
        return movieRepo.create(movieDTO)
    }

    @DeleteMapping("/{id}")
    suspend fun deleteMovie(@PathVariable("id") movieId: Int): Int {
        return movieRepo.deleteById(movieId)
    }
}
