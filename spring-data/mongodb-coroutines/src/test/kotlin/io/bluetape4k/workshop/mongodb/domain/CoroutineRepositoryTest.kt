package io.bluetape4k.workshop.mongodb.domain

import io.bluetape4k.coroutines.flow.extensions.log
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.mongodb.AbstractMongodbTest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.ReactiveMongoOperations
import org.springframework.data.mongodb.core.dropCollection

class CoroutineRepositoryTest @Autowired constructor(
    private val repository: PersonCoroutineRepository,
    private val operations: ReactiveMongoOperations,
): AbstractMongodbTest() {

    companion object: KLogging()

    @BeforeEach
    fun beforeEach() {
        runBlocking {
            operations.dropCollection<Person>().awaitSingleOrNull()
        }
    }

    @Test
    fun `find one person`() = runSuspendIO {
        val person = repository.save(newPerson())

        val loaded = repository.findPersonByFirstname(person.firstname!!)
        loaded.shouldNotBeNull() shouldBeEqualTo person
    }

    @Test
    fun `find persons as flow`() = runSuspendIO {
        List(10) {
            launch {
                repository.save(newPerson())
            }
        }.joinAll()

        val person1 = repository.save(Person("Sunghyouk", "Bae"))
        val person2 = repository.save(Person("Jehyoung", "Bae"))

        List(10) {
            launch {
                repository.save(newPerson())
            }
        }.joinAll()

        val persons = repository.findByLastname("Bae").log("Bae").toList()
        persons shouldHaveSize 2
        persons shouldBeEqualTo listOf(person1, person2)

        val sunghyouk = repository.findAllByFirstname("Sunghyouk")
            .log("Sunghyouk")
            .toList()

        sunghyouk shouldHaveSize 1
        sunghyouk.single() shouldBeEqualTo person1
    }
}
