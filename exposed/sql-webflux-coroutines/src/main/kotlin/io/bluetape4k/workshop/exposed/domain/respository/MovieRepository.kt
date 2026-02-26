package io.bluetape4k.workshop.exposed.domain.respository

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.dto.ActorDTO
import io.bluetape4k.workshop.exposed.domain.dto.MovieActorCountDTO
import io.bluetape4k.workshop.exposed.domain.dto.MovieDTO
import io.bluetape4k.workshop.exposed.domain.dto.MovieWithActorDTO
import io.bluetape4k.workshop.exposed.domain.dto.MovieWithProducingActorDTO
import io.bluetape4k.workshop.exposed.domain.mapper.toMovieDTO
import io.bluetape4k.workshop.exposed.domain.schema.Actors
import io.bluetape4k.workshop.exposed.domain.schema.ActorsInMovies
import io.bluetape4k.workshop.exposed.domain.schema.Movies
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.springframework.stereotype.Repository

@Suppress("DEPRECATION")
@Repository
class MovieRepository {

    companion object: KLogging() {
        private val MovieInnerJoinActors by lazy {
            Movies
                .innerJoin(ActorsInMovies)
                .innerJoin(Actors)
        }
    }

    suspend fun findById(movieId: Int): MovieDTO? = newSuspendedTransaction {
        log.debug { "Find Movie by id. id: $movieId" }
        Movies.selectAll()
            .where { Movies.id eq movieId }
            .firstOrNull()
            ?.toMovieDTO()
    }

    suspend fun searchMovie(params: Map<String, String>): List<MovieDTO> = newSuspendedTransaction {
        log.debug { "Search Movie by params. params: $params" }

        val query = Movies.selectAll()

        params.forEach { (key, value) ->
            when (key) {
                "id" -> query.andWhere { Movies.id eq value.toInt() }
                "name" -> query.andWhere { Movies.name eq value }
                "producerName" -> query.andWhere { Movies.producerName eq value }
                "releaseDate" -> query.andWhere { Movies.releaseDate eq LocalDateTime.parse(value) }
            }
        }

        query.map { it.toMovieDTO() }
    }

    suspend fun create(movie: MovieDTO): MovieDTO? = newSuspendedTransaction {
        val movieId = Movies.insertAndGetId {
            it[Movies.name] = movie.name
            it[Movies.producerName] = movie.producerName
            if (movie.releaseDate.isNotBlank()) {
                it[Movies.releaseDate] = LocalDate.parse(movie.releaseDate).atTime(0, 0)
            }
        }

        movie.copy(id = movieId.value)
    }

    suspend fun deleteById(movieId: Int): Int = newSuspendedTransaction {
        Movies.deleteWhere { Movies.id eq movieId }
    }

    suspend fun getAllMoviesWithActors(): List<MovieWithActorDTO> {
        log.debug { "Get all movies with actors." }

        return newSuspendedTransaction {
            MovieInnerJoinActors
                .selectAll()
                .groupingBy { it[Movies.id] }
                .fold(mutableListOf<MovieWithActorDTO>()) { acc, element ->
                    val lastMovieId = acc.lastOrNull()?.id
                    if (lastMovieId != element[Movies.id].value) {
                        val movie = MovieWithActorDTO(
                            id = element[Movies.id].value,
                            name = element[Movies.name],
                            producerName = element[Movies.producerName],
                            releaseDate = element[Movies.releaseDate].toString(),
                        )
                        acc.add(movie)
                    } else {
                        acc.lastOrNull()?.actors?.let {
                            val actor = ActorDTO(
                                id = element[Actors.id].value,
                                firstName = element[Actors.firstName],
                                lastName = element[Actors.lastName],
                                dateOfBirth = element[Actors.dateOfBirth].toString()
                            )
                            it.add(actor)
                        }
                    }
                    acc
                }
                .values
                .flatten()
        }
    }

    suspend fun getMovieWithActors(movieId: Int): MovieWithActorDTO? = newSuspendedTransaction {
        log.debug { "Get movie with actors. movieId=$movieId" }

        val query = MovieInnerJoinActors
            .selectAll()
            .where { Movies.id eq movieId }

        log.debug { "query: ${query.prepareSQL(this, true)}" }

        query
            .groupingBy { it[Movies.id] }
            .fold(mutableListOf<MovieWithActorDTO>()) { acc, row ->
                val prevId = acc.lastOrNull()?.id
                if (prevId != row[Movies.id].value) {
                    val movie = MovieWithActorDTO(
                        id = row[Movies.id].value,
                        name = row[Movies.name],
                        producerName = row[Movies.producerName],
                        releaseDate = row[Movies.releaseDate].toString(),
                    )
                    acc.add(movie)
                } else {
                    val actor = ActorDTO(
                        id = row[Actors.id].value,
                        firstName = row[Actors.firstName],
                        lastName = row[Actors.lastName],
                        dateOfBirth = row[Actors.dateOfBirth].toString()
                    )
                    acc.lastOrNull()?.actors?.add(actor)
                }
                acc
            }
            .asSequence()
    }.firstOrNull()?.value?.firstOrNull()

    suspend fun getMovieActorsCount(): List<MovieActorCountDTO> = newSuspendedTransaction {
        log.debug { "Get movie with actors count" }
        MovieInnerJoinActors
            .select(Movies.name, Actors.firstName.count())
            .groupBy(Movies.name)
            .map {
                MovieActorCountDTO(
                    movieName = it[Movies.name],
                    actorCount = it[Actors.firstName.count()].toInt()
                )
            }
    }

    suspend fun findMoviesWithActingProducers(): List<MovieWithProducingActorDTO> = newSuspendedTransaction {
        log.debug { "Find movies with acting producers" }

        MovieInnerJoinActors
            .select(Movies.name, Actors.firstName, Actors.lastName)
            .map {
                MovieWithProducingActorDTO(
                    movieName = it[Movies.name],
                    producerActorName = "${it[Actors.firstName]} ${it[Actors.lastName]}"
                )
            }
    }
}
