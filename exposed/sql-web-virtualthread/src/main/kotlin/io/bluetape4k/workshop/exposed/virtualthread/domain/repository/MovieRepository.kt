package io.bluetape4k.workshop.exposed.virtualthread.domain.repository

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.utils.ShutdownQueue
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.ActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieActorCountDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieWithActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieWithProducingActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.mapper.toMovieDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.schema.Actors
import io.bluetape4k.workshop.exposed.virtualthread.domain.schema.ActorsInMovies
import io.bluetape4k.workshop.exposed.virtualthread.domain.schema.Movies
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.Executors

@Repository
class MovieRepository(private val db: Database) {

    companion object: KLogging() {
        private val virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()
            .apply {
                ShutdownQueue.register(this)
            }
    }

    fun findById(movieId: Int): VirtualFuture<MovieDTO?> = virtualFuture(virtualExecutor) {
        log.debug { "Find Movie by id. id: $movieId" }

        transaction(db) {
            Movies.selectAll().where { Movies.id eq movieId }.firstOrNull()?.toMovieDTO()
        }
    }

    fun searchMovie(params: Map<String, String?>): VirtualFuture<List<MovieDTO>> = virtualFuture(virtualExecutor) {
        log.debug { "Search Movie by params. params: $params" }

        transaction(db) {
            val query = Movies.selectAll()

            params.forEach { (key, value) ->
                when (key) {
                    Movies::id.name -> value?.run { query.andWhere { Movies.id eq value.toInt() } }
                    Movies::name.name -> value?.run { query.andWhere { Movies.name eq value } }
                    Movies::producerName.name -> value?.run { query.andWhere { Movies.producerName eq value } }
                    Movies::releaseDate.name -> value?.run {
                        query.andWhere { Movies.releaseDate eq LocalDateTime.parse(value) }
                    }
                }
            }

            query.map { it.toMovieDTO() }
        }
    }

    fun create(movie: MovieDTO): VirtualFuture<MovieDTO> = virtualFuture(virtualExecutor) {
        transaction(db) {
            val movieId = Movies.insert {
                it[Movies.name] = movie.name
                it[Movies.producerName] = movie.producerName
                if (movie.releaseDate.isNotBlank()) {
                    it[Movies.releaseDate] = LocalDate.parse(movie.releaseDate).atTime(0, 0)
                }
            } get Movies.id

            movie.copy(id = movieId.value)
        }
    }

    fun deleteById(movieId: Int): VirtualFuture<Int> = virtualFuture(virtualExecutor) {
        transaction(db) {
            Movies.deleteWhere { Movies.id eq movieId }
        }
    }

    fun getAllMoviesWithActors(): VirtualFuture<List<MovieWithActorDTO>> = virtualFuture(virtualExecutor) {
        log.debug { "Get all movies with actors." }

        transaction(db) {
            Movies.innerJoin(ActorsInMovies).innerJoin(Actors).selectAll().toList().groupingBy { it[Movies.id] }
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
                }.values.flatten()
        }
    }

    fun getMovieWithActors(movieId: Int): VirtualFuture<MovieWithActorDTO?> = virtualFuture(virtualExecutor) {
        log.debug { "Get movie with actors. movieId=$movieId" }

        transaction(db) {
            val query = Movies
                .innerJoin(ActorsInMovies)
                .innerJoin(Actors)
                .selectAll()
                .where { Movies.id eq movieId }

            log.debug { "query: ${query.prepareSQL(this, true)}" }

            query.groupingBy { it[Movies.id] }.fold(mutableListOf<MovieWithActorDTO>()) { acc, row ->
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
            }.toList()
        }.firstOrNull()?.second?.firstOrNull()
    }

    fun getMovieActorsCount(): VirtualFuture<List<MovieActorCountDTO>> = virtualFuture(virtualExecutor) {
        log.debug { "Get movie with actors count" }

        transaction(db) {
            Movies
                .innerJoin(ActorsInMovies)
                .innerJoin(Actors)
                .select(Movies.name, Actors.firstName.count())
                .groupBy(Movies.name).map {
                    MovieActorCountDTO(
                        movieName = it[Movies.name], actorCount = it[Actors.firstName.count()].toInt()
                    )
                }
        }
    }

    fun findMoviesWithActingProducers(): VirtualFuture<List<MovieWithProducingActorDTO>> =
        virtualFuture(virtualExecutor) {
            log.debug { "Find movies with acting producers" }

            transaction {
                Movies
                    .innerJoin(ActorsInMovies)
                    .innerJoin(Actors)
                    .select(Movies.name, Actors.firstName, Actors.lastName).map {
                        MovieWithProducingActorDTO(
                            movieName = it[Movies.name],
                            producerActorName = "${it[Actors.firstName]} ${it[Actors.lastName]}"
                        )
                    }
            }
        }
}
