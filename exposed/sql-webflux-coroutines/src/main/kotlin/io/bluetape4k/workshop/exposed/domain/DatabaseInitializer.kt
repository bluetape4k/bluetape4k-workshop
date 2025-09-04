package io.bluetape4k.workshop.exposed.domain

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.exposed.domain.dto.ActorDTO
import io.bluetape4k.workshop.exposed.domain.dto.MovieWithActorDTO
import io.bluetape4k.workshop.exposed.domain.mapper.toActorDTO
import io.bluetape4k.workshop.exposed.domain.mapper.toMovieDTO
import io.bluetape4k.workshop.exposed.domain.schema.Actors
import io.bluetape4k.workshop.exposed.domain.schema.ActorsInMovies
import io.bluetape4k.workshop.exposed.domain.schema.Movies
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 데이터베이스 초기화를 수행하고, 테스트 데이터를 삽입하는 클래스
 */
@Component
@Transactional
class DatabaseInitializer: ApplicationRunner {

    companion object: KLogging()

    override fun run(args: ApplicationArguments?) {
        createSchemaAndTestData()
    }

    /**
     * 데이터베이스 스키마를 생성 또는 Update하고, 테스트 데이터를 추가합니다.
     */
    fun createSchemaAndTestData() {
        createSchema()
        insertTestData()
    }

    private fun createSchema() {
        log.info { "Creating schema and test data ..." }

        transaction {
            // SchemaUtils.createMissingTablesAndColumns(Actors, Movies, ActorsInMovies)
            val stmts = MigrationUtils.statementsRequiredForDatabaseMigration(Actors, Movies, ActorsInMovies)
            if (stmts.isNotEmpty()) {
                log.info { "Executing migration statements: $stmts" }
                exec(stmts.joinToString(";"))
            }
        }
    }

    private fun insertTestData() {

        val totalActors = transaction {
            Actors.selectAll().count()
        }

        if (totalActors > 0) {
            log.info { "There appears to be data already present, not inserting test data!" }
            return
        }

        log.info { "Inserting actors and movies" }

        val johnnyDepp = ActorDTO("Johnny", "Depp", "1979-10-28")
        val bradPitt = ActorDTO("Brad", "Pitt", "1982-05-16")
        val angelinaJolie = ActorDTO("Angelina", "Jolie", "1983-11-10")
        val jenniferAniston = ActorDTO("Jennifer", "Aniston", "1975-07-23")
        val angelinaGrace = ActorDTO("Angelina", "Grace", "1988-09-02")
        val craigDaniel = ActorDTO("Craig", "Daniel", "1970-11-12")
        val ellenPaige = ActorDTO("Ellen", "Paige", "1981-12-20")
        val russellCrowe = ActorDTO("Russell", "Crowe", "1970-01-20")
        val edwardNorton = ActorDTO("Edward", "Norton", "1975-04-03")

        val actors = listOf(
            johnnyDepp,
            bradPitt,
            angelinaJolie,
            jenniferAniston,
            angelinaGrace,
            craigDaniel,
            ellenPaige,
            russellCrowe,
            edwardNorton
        )

        val movies = listOf(
            MovieWithActorDTO(
                "Gladiator",
                johnnyDepp.firstName,
                "2000-05-01",
                mutableListOf(russellCrowe, ellenPaige, craigDaniel)
            ),
            MovieWithActorDTO(
                "Guardians of the galaxy",
                johnnyDepp.firstName,
                "2014-07-21",
                mutableListOf(angelinaGrace, bradPitt, ellenPaige, angelinaJolie, johnnyDepp)
            ),
            MovieWithActorDTO(
                "Fight club",
                craigDaniel.firstName,
                "1999-09-13",
                mutableListOf(bradPitt, jenniferAniston, edwardNorton)
            ),
            MovieWithActorDTO(
                "13 Reasons Why",
                "Suzuki",
                "2016-01-01",
                mutableListOf(angelinaJolie, jenniferAniston)
            )
        )

        transaction {
            val actorDTOs = Actors.batchInsert(actors) {
                this[Actors.firstName] = it.firstName
                this[Actors.lastName] = it.lastName
                it.dateOfBirth?.let { birthDay ->
                    this[Actors.dateOfBirth] = LocalDate.parse(birthDay)
                }
            }.map { it.toActorDTO() }

            val movieDTOs = Movies.batchInsert(movies) {
                this[Movies.name] = it.name
                this[Movies.producerName] = it.producerName
                this[Movies.releaseDate] = LocalDate.parse(it.releaseDate).atTime(0, 0)
            }.map { it.toMovieDTO() }

            val movieActorIds = movies.mapNotNull { movie ->
                val movieId = movieDTOs.find { it.name == movie.name }?.id

                val actorIds = movie.actors.mapNotNull { actor ->
                    actorDTOs.find { it.firstName == actor.firstName && it.lastName == actor.lastName }?.id
                }
                if (movieId != null) {
                    actorIds.map { movieId to it }
                } else {
                    null
                }
            }.flatten()

            ActorsInMovies.batchInsert(movieActorIds) {
                this[ActorsInMovies.movieId] = it.first
                this[ActorsInMovies.actorId] = it.second
            }
        }
    }
}
