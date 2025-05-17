package io.bluetape4k.workshop.exposed.virtualthread.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.repository.MovieRepository
import jakarta.servlet.http.HttpServletRequest
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
class MovieController(private val movieRepo: MovieRepository) {

    companion object: KLoggingChannel()

    @GetMapping("/{id}")
    fun getMovieById(@PathVariable("id") movieId: Int): MovieDTO? {
        return movieRepo.findById(movieId).await()
    }

    @GetMapping
    fun searchMovies(request: HttpServletRequest): List<MovieDTO> {
        val params = request.parameterMap.map { it.key to it.value.firstOrNull() }.toMap()
        log.debug { "Search Movies... params=$params" }

        return movieRepo.searchMovie(params).await()
    }

    @PostMapping
    fun createMovie(@RequestBody movieDTO: MovieDTO): MovieDTO? {
        return movieRepo.create(movieDTO).await()
    }

    @DeleteMapping("/{id}")
    fun deleteMovie(@PathVariable("id") movieId: Int): Int {
        return movieRepo.deleteById(movieId).await()
    }
}
