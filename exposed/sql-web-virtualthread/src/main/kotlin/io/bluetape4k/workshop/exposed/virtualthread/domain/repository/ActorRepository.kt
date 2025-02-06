package io.bluetape4k.workshop.exposed.virtualthread.domain.repository

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.utils.ShutdownQueue
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.ActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.mapper.toActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.schema.Actors
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.concurrent.Executors

@Repository
class ActorRepository(private val db: Database) {

    companion object: KLogging() {
        private val virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()
            .apply {
                ShutdownQueue.register(this)
            }
    }

    fun findById(id: Int): VirtualFuture<ActorDTO?> = virtualFuture(virtualExecutor) {
        log.debug { "Find Actor by id. id: $id" }

        transaction(db) {
            Actors.selectAll()
                .where(Actors.id eq id)
                .firstOrNull()
                ?.toActorDTO()
        }
    }


    fun searchActor(params: Map<String, String?>): VirtualFuture<List<ActorDTO>> = virtualFuture(virtualExecutor) {
        log.debug { "Search Actor by params. params: $params" }

        transaction(db) {
            val query = Actors.selectAll()

            params.forEach { (key, value) ->
                when (key) {
                    Actors::id.name -> value?.run { query.andWhere { Actors.id eq value.toInt() } }
                    Actors::firstName.name -> value?.run { query.andWhere { Actors.firstName eq value } }
                    Actors::lastName.name -> value?.run { query.andWhere { Actors.lastName eq value } }
                    Actors::dateOfBirth.name -> value?.run {
                        query.andWhere { Actors.dateOfBirth eq LocalDate.parse(value) }
                    }
                }
            }

            query.map { it.toActorDTO() }
        }
    }

    fun create(actor: ActorDTO): VirtualFuture<ActorDTO> = virtualFuture(virtualExecutor) {
        log.debug { "Create Actor. actor: $actor" }

        transaction(db) {
            val actorId = Actors.insert {
                it[Actors.firstName] = actor.firstName
                it[Actors.lastName] = actor.lastName
                actor.dateOfBirth?.let { dateOfBirth ->
                    it[Actors.dateOfBirth] = LocalDate.parse(dateOfBirth)
                }
            } get Actors.id

            // 이렇게 새로 읽는 것이 정식이지만, 단순히 actor.copy(id=actorId)로 반환해도 무방합니다.
            Actors.selectAll()
                .where { Actors.id eq actorId }
                .first()
                .toActorDTO()
        }
    }

    /**
     * [actorId]에 해당하는 배우 정보를 삭제하고, 삭제된 Actor 수를 반환합니다 (0 또는 1).
     */
    fun deleteById(actorId: Int): VirtualFuture<Int> = virtualFuture(virtualExecutor) {
        log.debug { "Delete Actor by id. actorId: $actorId" }

        transaction(db) {
            Actors.deleteWhere { Actors.id eq actorId }
        }
    }
}
