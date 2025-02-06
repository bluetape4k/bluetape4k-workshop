package io.bluetape4k.workshop.exposed.virtualthread.domain

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.ActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.MovieWithActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.schema.Actors
import io.bluetape4k.workshop.exposed.virtualthread.domain.schema.ActorsInMovies
import io.bluetape4k.workshop.exposed.virtualthread.domain.schema.Movies
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 데이터베이스 초기화를 수행하고, 테스트 데이터를 삽입하는 클래스
 */
@Component
class DatabaseInitializer(
    private val database: Database,
) {

    companion object: KLogging()

    /**
     * 데이터베이스 스키마를 생성 또는 Update하고, 테스트 데이터를 추가합니다.
     */
    fun createSchemaAndTestData() {
        createSchema(database)
        insertTestData(database)
    }

    private fun createSchema(database: Database) {
        log.info { "Creating schema and test data ..." }

        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(Actors, Movies, ActorsInMovies)
        }
    }

    private fun insertTestData(database: Database) {

        val totalActors = transaction(database) {
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

        transaction(database) {
            Actors.batchInsert(actors) {
                this[Actors.firstName] = it.firstName
                this[Actors.lastName] = it.lastName
                it.dateOfBirth?.let { birthDay ->
                    this[Actors.dateOfBirth] = LocalDate.parse(birthDay)
                }
            }

            Movies.batchInsert(movies) {
                this[Movies.name] = it.name
                this[Movies.producerName] = it.producerName
                this[Movies.releaseDate] = LocalDate.parse(it.releaseDate).atTime(0, 0)
            }

            movies.forEach { movie ->
                val movieId = Movies
                    .select(Movies.id)
                    .where { Movies.name eq movie.name }
                    .first()[Movies.id]

                movie.actors.forEach { actor ->
                    val actorId = Actors
                        .select(Actors.id)
                        .where { (Actors.firstName eq actor.firstName) and (Actors.lastName eq actor.lastName) }
                        .first()[Actors.id]

                    ActorsInMovies.insert {
                        it[ActorsInMovies.actorId] = actorId.value
                        it[ActorsInMovies.movieId] = movieId.value
                    }
                }
            }
        }
    }
}
