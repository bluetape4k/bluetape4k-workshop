package io.bluetape4k.workshop.exposed.domain.respository

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.dto.ActorDTO
import io.bluetape4k.workshop.exposed.domain.mapper.toActorDTO
import io.bluetape4k.workshop.exposed.domain.schema.Actors
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional(readOnly = true)
class ActorRepository {

    companion object: KLogging()

    suspend fun findById(id: Int): ActorDTO? {
        log.debug { "Find Actor by id. id: $id" }

        return newSuspendedTransaction {
            Actors.selectAll()
                .where { Actors.id eq id }
                .firstOrNull()
                ?.toActorDTO()
        }
    }

    suspend fun searchActor(params: Map<String, String>): List<ActorDTO> {
        log.debug { "Search Actor by params. params: $params" }

        return newSuspendedTransaction {
            val query = Actors.selectAll()

            params.forEach { (key, value) ->
                when (key) {
                    "id" -> query.andWhere { Actors.id eq value.toInt() }
                    "firstName" -> query.andWhere { Actors.firstName eq value }
                    "lastName" -> query.andWhere { Actors.lastName eq value }
                    "dateOfBirth" -> query.andWhere { Actors.dateOfBirth eq LocalDate.parse(value) }
                }
            }

            query.map { it.toActorDTO() }
        }
    }

    @Transactional
    suspend fun create(actor: ActorDTO): ActorDTO {
        log.debug { "Create Actor. actor: $actor" }

        return newSuspendedTransaction {
            val actorId = Actors.insertAndGetId {
                it[Actors.firstName] = actor.firstName
                it[Actors.lastName] = actor.lastName
                actor.dateOfBirth?.let { dateOfBirth ->
                    it[Actors.dateOfBirth] = LocalDate.parse(dateOfBirth)
                }
            }

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
    @Transactional
    suspend fun deleteById(actorId: Int): Int {
        log.debug { "Delete Actor by id. actorId: $actorId" }

        return newSuspendedTransaction {
            Actors.deleteWhere { Actors.id eq actorId }
        }
    }
}
