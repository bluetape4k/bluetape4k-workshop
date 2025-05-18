package io.bluetape4k.workshop.exposed.virtualthread.domain.repository

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.virtualthread.AbstractExposedTest
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.ActorDTO
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class ActorRepositoryTest(
    @Autowired private val actorRepo: ActorRepository,
): AbstractExposedTest() {

    companion object: KLoggingChannel() {
        fun newActor(): ActorDTO = ActorDTO(
            firstName = faker.name().firstName(),
            lastName = faker.name().lastName(),
            dateOfBirth = faker.timeAndDate().birthday(20, 80).toString()
        )
    }

    @Test
    fun `find actor by id`() {
        val actorId = 1

        val actor = actorRepo.findById(actorId).await()
        actor.shouldNotBeNull()
        actor.id shouldBeEqualTo actorId
    }


    @Test
    fun `search actors by lastName`() = runSuspendIO {
        val params = mapOf("lastName" to "Depp")
        val actors = actorRepo.searchActor(params).await()

        actors.shouldNotBeEmpty()
        actors.forEach {
            log.debug { "actor: $it" }
        }
    }

    @Test
    fun `create new actor`() = runSuspendIO {
        val newActor = newActor()

        val savedActor = actorRepo.create(newActor).await()
        savedActor shouldBeEqualTo newActor.copy(id = savedActor.id)
    }

    @Test
    fun `delete actor by id`() = runSuspendIO {
        val newActor = newActor()
        val savedActor = actorRepo.create(newActor).await()
        savedActor.shouldNotBeNull()

        val deletedCount = actorRepo.deleteById(savedActor.id!!).await()
        deletedCount shouldBeEqualTo 1
    }
}
