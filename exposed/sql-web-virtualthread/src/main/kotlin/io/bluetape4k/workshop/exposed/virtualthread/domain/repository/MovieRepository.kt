package io.bluetape4k.workshop.exposed.virtualthread.domain.repository

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.utils.ShutdownQueue
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.ActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieActorCountDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieWithActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieWithProducingActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.mapper.toMovieDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.schema.ActorInMovieTable
import io.bluetape4k.workshop.exposed.virtualthread.domain.schema.ActorTable
import io.bluetape4k.workshop.exposed.virtualthread.domain.schema.MovieTable
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.Executors

@Repository
class MovieRepository(private val db: Database) {

    companion object: KLoggingChannel() {
        private val virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()
            .apply {
                ShutdownQueue.register(this)
            }
    }

    fun findById(movieId: Int): VirtualFuture<MovieDTO?> = virtualFuture(virtualExecutor) {
        log.debug { "Find Movie by id. id: $movieId" }

        transaction(db) {
            // Use DAO
            // Movie.findById(movieId)?.toMovieDTO()

            // Use Exposed SQL DSL
            MovieTable.selectAll()
                .where { MovieTable.id eq movieId }
                .firstOrNull()
                ?.toMovieDTO()
        }
    }

    fun searchMovie(params: Map<String, String?>): VirtualFuture<List<MovieDTO>> = virtualFuture(virtualExecutor) {
        log.debug { "Search Movie by params. params: $params" }

        transaction(db) {
            val query = MovieTable.selectAll()

            params.forEach { (key, value) ->
                when (key) {
                    MovieTable::id.name           -> value?.run { query.andWhere { MovieTable.id eq value.toInt() } }
                    MovieTable::name.name         -> value?.run { query.andWhere { MovieTable.name eq value } }
                    MovieTable::producerName.name -> value?.run { query.andWhere { MovieTable.producerName eq value } }
                    MovieTable::releaseDate.name  -> value?.run {
                        query.andWhere { MovieTable.releaseDate eq LocalDateTime.parse(value) }
                    }
                }
            }

            query.map { it.toMovieDTO() }
        }
    }

    fun create(movie: MovieDTO): VirtualFuture<MovieDTO> = virtualFuture(virtualExecutor) {
        transaction(db) {
            val movieId = MovieTable.insertAndGetId {
                it[MovieTable.name] = movie.name
                it[MovieTable.producerName] = movie.producerName
                if (movie.releaseDate.isNotBlank()) {
                    it[MovieTable.releaseDate] = LocalDate.parse(movie.releaseDate).atTime(0, 0)
                }
            }

            movie.copy(id = movieId.value)
        }
    }

    fun deleteById(movieId: Int): VirtualFuture<Int> = virtualFuture(virtualExecutor) {
        transaction(db) {
            MovieTable.deleteWhere { MovieTable.id eq movieId }
        }
    }

    fun getAllMoviesWithActors(): VirtualFuture<List<MovieWithActorDTO>> = virtualFuture(virtualExecutor) {
        log.debug { "Get all movies with actors." }

        transaction(db) {
            MovieTable
                .innerJoin(ActorInMovieTable)
                .innerJoin(ActorTable)
                .selectAll()
                .toList()
                .groupingBy { it[MovieTable.id] } // 이 것보다 One-To-Many 를 집계하는 함수를 사용하는 것을 추천한다.
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
                }.values.flatten()
        }
    }

    fun getMovieWithActors(movieId: Int): VirtualFuture<MovieWithActorDTO?> = virtualFuture(virtualExecutor) {
        log.debug { "Get movie with actors. movieId=$movieId" }

        transaction(db) {
            val query = MovieTable
                .innerJoin(ActorInMovieTable)
                .innerJoin(ActorTable)
                .selectAll()
                .where { MovieTable.id eq movieId }

            log.debug { "query: ${query.prepareSQL(this, true)}" }

            query.groupingBy { it[MovieTable.id] }
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
                }.toList()
        }.firstOrNull()?.second?.firstOrNull()
    }

    fun getMovieActorsCount(): VirtualFuture<List<MovieActorCountDTO>> = virtualFuture(virtualExecutor) {
        log.debug { "Get movie with actors count" }

        transaction(db) {
            MovieTable
                .innerJoin(ActorInMovieTable)
                .innerJoin(ActorTable)
                .select(MovieTable.name, ActorTable.firstName.count())
                .groupBy(MovieTable.name).map {
                    MovieActorCountDTO(
                        movieName = it[MovieTable.name], actorCount = it[ActorTable.firstName.count()].toInt()
                    )
                }
        }
    }

    fun findMoviesWithActingProducers(): VirtualFuture<List<MovieWithProducingActorDTO>> =
        virtualFuture(virtualExecutor) {
            log.debug { "Find movies with acting producers" }

            transaction {
                MovieTable
                    .innerJoin(ActorInMovieTable)
                    .innerJoin(ActorTable)
                    .select(MovieTable.name, ActorTable.firstName, ActorTable.lastName)
                    .map {
                        MovieWithProducingActorDTO(
                            movieName = it[MovieTable.name],
                            producerActorName = "${it[ActorTable.firstName]} ${it[ActorTable.lastName]}"
                        )
                    }
            }
        }
}
