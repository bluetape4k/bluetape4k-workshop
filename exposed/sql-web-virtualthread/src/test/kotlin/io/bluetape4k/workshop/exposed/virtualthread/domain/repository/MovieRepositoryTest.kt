package io.bluetape4k.workshop.exposed.virtualthread.domain.repository

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.virtualthread.AbstractExposedTest
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieDTO
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class MovieRepositoryTest(
    @Autowired private val movieRepo: MovieRepository,
): AbstractExposedTest() {

    companion object: KLoggingChannel() {
        private fun newMovie(): MovieDTO = MovieDTO(
            name = faker.book().title(),
            producerName = faker.name().fullName(),
            releaseDate = faker.timeAndDate().birthday(20, 80).toString()
        )
    }

    @Test
    fun `find movie by id`() {
        val movieId = 1

        val movie = movieRepo.findById(movieId).await()

        log.debug { "movie: $movie" }
        movie.shouldNotBeNull()
        movie.id shouldBeEqualTo movieId
    }

    @Test
    fun `search movies`() {
        val params = mapOf("producerName" to "Johnny")

        val movies = movieRepo.searchMovie(params).await()
        movies.forEach {
            log.debug { "movie: $it" }
        }
        movies.shouldNotBeEmpty() shouldHaveSize 2
    }

    @Test
    fun `create movie`() {
        val newMovie = newMovie()
        val saved = movieRepo.create(newMovie).await()

        saved.shouldNotBeNull()
        saved shouldBeEqualTo newMovie.copy(id = saved.id)
    }

    @Test
    fun `delete movie`() {
        transaction {
            val newMovie = newMovie()
            val saved = movieRepo.create(newMovie).await()

            val deletedCount = movieRepo.deleteById(saved.id!!).await()
            deletedCount shouldBeEqualTo 1
        }
    }

    @Test
    fun `get all movies and actors`() = runSuspendIO {
        val movieWithActors = movieRepo.getAllMoviesWithActors().await()

        movieWithActors.shouldNotBeEmpty()
        movieWithActors.forEach { movie ->
            log.debug { "movie: ${movie.name}" }
            movie.actors.shouldNotBeEmpty()
            movie.actors.forEach { actor ->
                log.debug { "  actor: ${actor.firstName} ${actor.lastName}" }
            }
        }
    }

    @Test
    fun `get movie and actors`() = runSuspendIO {
        val movieId = 1

        val movieWithActors = movieRepo.getMovieWithActors(movieId).await()
        log.debug { "movieWithActors: $movieWithActors" }

        movieWithActors.shouldNotBeNull()
        movieWithActors.id shouldBeEqualTo movieId
        movieWithActors.actors.shouldNotBeEmpty()
    }

    @Test
    fun `get movie and actors count`() = runSuspendIO {
        val movieActorsCount = movieRepo.getMovieActorsCount().await()
        movieActorsCount.shouldNotBeEmpty()
        movieActorsCount.forEach {
            log.debug { "movie=${it.movieName}, actor count=${it.actorCount}" }
        }
    }

    @Test
    fun `find movies with acting producers`() = runSuspendIO {
        val results = movieRepo.findMoviesWithActingProducers().await()

        results.shouldNotBeEmpty()

        results.forEach {
            log.debug { "movie with acting producer=$it" }
        }
    }
}
