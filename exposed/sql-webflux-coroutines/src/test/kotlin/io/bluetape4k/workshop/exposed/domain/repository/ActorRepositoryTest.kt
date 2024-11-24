package io.bluetape4k.workshop.exposed.domain.repository

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedSqlTest
import io.bluetape4k.workshop.exposed.domain.dto.ActorDTO
import io.bluetape4k.workshop.exposed.domain.respository.ActorRepository
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class ActorRepositoryTest(
    @Autowired private val actorRepo: ActorRepository,
): AbstractExposedSqlTest() {

    companion object: KLogging() {
        fun newActor(): ActorDTO = ActorDTO(
            firstName = faker.name().firstName(),
            lastName = faker.name().lastName(),
            dateOfBirth = faker.timeAndDate().birthday(20, 80).toString()
        )
    }

    @Test
    fun `find actor by id`() = runSuspendIO {
        val actorId = 1

        val actor = actorRepo.findById(actorId)
        actor.shouldNotBeNull()
        actor.id shouldBeEqualTo actorId
    }

    @Test
    fun `search actors by lastName`() = runSuspendIO {
        val params = mapOf("lastName" to "Depp")
        val actors = actorRepo.searchActor(params)

        actors.shouldNotBeEmpty()
        actors.forEach {
            log.debug { "actor: $it" }
        }
    }

    @Test
    fun `create new actor`() = runSuspendIO {
        val newActor = newActor()

        val savedActor = actorRepo.create(newActor)
        savedActor shouldBeEqualTo newActor.copy(id = savedActor.id)
    }

    @Test
    fun `delete actor by id`() = runSuspendIO {
        val newActor = newActor()
        val savedActor = actorRepo.create(newActor)
        savedActor.shouldNotBeNull()

        val deletedCount = actorRepo.deleteById(savedActor.id!!)
        deletedCount shouldBeEqualTo 1
    }
}
