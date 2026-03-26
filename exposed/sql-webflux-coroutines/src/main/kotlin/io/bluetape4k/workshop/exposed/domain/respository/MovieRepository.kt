package io.bluetape4k.workshop.exposed.domain.respository

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.dto.ActorDTO
import io.bluetape4k.workshop.exposed.domain.dto.MovieActorCountDTO
import io.bluetape4k.workshop.exposed.domain.dto.MovieDTO
import io.bluetape4k.workshop.exposed.domain.dto.MovieWithActorDTO
import io.bluetape4k.workshop.exposed.domain.dto.MovieWithProducingActorDTO
import io.bluetape4k.workshop.exposed.domain.mapper.toMovieDTO
import io.bluetape4k.workshop.exposed.domain.schema.ActorInMovieTable
import io.bluetape4k.workshop.exposed.domain.schema.ActorTable
import io.bluetape4k.workshop.exposed.domain.schema.MovieTable
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
            MovieTable
                .innerJoin(ActorInMovieTable)
                .innerJoin(ActorTable)
        }
    }

    suspend fun findById(movieId: Int): MovieDTO? = newSuspendedTransaction {
        log.debug { "Find Movie by id. id: $movieId" }
        MovieTable.selectAll()
            .where { MovieTable.id eq movieId }
            .firstOrNull()
            ?.toMovieDTO()
    }

    suspend fun searchMovie(params: Map<String, String>): List<MovieDTO> = newSuspendedTransaction {
        log.debug { "Search Movie by params. params: $params" }

        val query = MovieTable.selectAll()

        params.forEach { (key, value) ->
            when (key) {
                "id"           -> query.andWhere { MovieTable.id eq value.toInt() }
                "name"         -> query.andWhere { MovieTable.name eq value }
                "producerName" -> query.andWhere { MovieTable.producerName eq value }
                "releaseDate"  -> query.andWhere { MovieTable.releaseDate eq LocalDateTime.parse(value) }
            }
        }

        query.map { it.toMovieDTO() }
    }

    suspend fun create(movie: MovieDTO): MovieDTO? = newSuspendedTransaction {
        val movieId = MovieTable.insertAndGetId {
            it[MovieTable.name] = movie.name
            it[MovieTable.producerName] = movie.producerName
            if (movie.releaseDate.isNotBlank()) {
                it[MovieTable.releaseDate] = LocalDate.parse(movie.releaseDate).atTime(0, 0)
            }
        }

        movie.copy(id = movieId.value)
    }

    suspend fun deleteById(movieId: Int): Int = newSuspendedTransaction {
        MovieTable.deleteWhere { MovieTable.id eq movieId }
    }

    suspend fun getAllMoviesWithActors(): List<MovieWithActorDTO> {
        log.debug { "Get all movies with actors." }

        return newSuspendedTransaction {
            MovieInnerJoinActors
                .selectAll()
                .groupingBy { it[MovieTable.id] }
                .fold(mutableListOf<MovieWithActorDTO>()) { acc, element ->
                    val lastMovieId = acc.lastOrNull()?.id
                    if (lastMovieId != element[MovieTable.id].value) {
                        val movie = MovieWithActorDTO(
                            id = element[MovieTable.id].value,
                            name = element[MovieTable.name],
                            producerName = element[MovieTable.producerName],
                            releaseDate = element[MovieTable.releaseDate].toString(),
                        )
                        acc.add(movie)
                    } else {
                        acc.lastOrNull()?.actors?.let {
                            val actor = ActorDTO(
                                id = element[ActorTable.id].value,
                                firstName = element[ActorTable.firstName],
                                lastName = element[ActorTable.lastName],
                                dateOfBirth = element[ActorTable.dateOfBirth].toString()
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
            .where { MovieTable.id eq movieId }

        log.debug { "query: ${query.prepareSQL(this, true)}" }

        query
            .groupingBy { it[MovieTable.id] }
            .fold(mutableListOf<MovieWithActorDTO>()) { acc, row ->
                val prevId = acc.lastOrNull()?.id
                if (prevId != row[MovieTable.id].value) {
                    val movie = MovieWithActorDTO(
                        id = row[MovieTable.id].value,
                        name = row[MovieTable.name],
                        producerName = row[MovieTable.producerName],
                        releaseDate = row[MovieTable.releaseDate].toString(),
                    )
                    acc.add(movie)
                } else {
                    val actor = ActorDTO(
                        id = row[ActorTable.id].value,
                        firstName = row[ActorTable.firstName],
                        lastName = row[ActorTable.lastName],
                        dateOfBirth = row[ActorTable.dateOfBirth].toString()
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
            .select(MovieTable.name, ActorTable.firstName.count())
            .groupBy(MovieTable.name)
            .map {
                MovieActorCountDTO(
                    movieName = it[MovieTable.name],
                    actorCount = it[ActorTable.firstName.count()].toInt()
                )
            }
    }

    suspend fun findMoviesWithActingProducers(): List<MovieWithProducingActorDTO> = newSuspendedTransaction {
        log.debug { "Find movies with acting producers" }

        MovieInnerJoinActors
            .select(MovieTable.name, ActorTable.firstName, ActorTable.lastName)
            .map {
                MovieWithProducingActorDTO(
                    movieName = it[MovieTable.name],
                    producerActorName = "${it[ActorTable.firstName]} ${it[ActorTable.lastName]}"
                )
            }
    }
}
