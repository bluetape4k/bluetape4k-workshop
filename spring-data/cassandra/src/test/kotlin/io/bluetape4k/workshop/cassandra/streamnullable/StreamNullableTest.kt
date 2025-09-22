package io.bluetape4k.workshop.cassandra.streamnullable

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.cassandra.AbstractCassandraTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [StreamNullableTestConfiguration::class])
class StreamNullableTest(
    @param:Autowired private val repository: PersonRepository,
): AbstractCassandraTest() {

    companion object: KLoggingChannel() {
        private fun newPerson(id: String = "1"): Person =
            Person(id, faker.name().firstName(), faker.name().lastName())
    }

    @BeforeEach
    fun beforeEach() {
        runCatching { repository.deleteAll() }
    }

    @Test
    fun `provide find one with nullable`() {
        val homer = repository.save(newPerson("1"))

        repository.findById("1").shouldNotBeNull()
        repository.findById(homer.id + 1).shouldBeNull()
    }

    @Test
    fun `invoke default function`() {
        val homer = repository.save(newPerson("2"))
        val loaded = repository.findByPerson(homer)

        loaded shouldBeEqualTo homer
    }

    @Test
    fun `use Java8 Stream with custom query`() {
        val homer = repository.save(newPerson("3"))
        val bart = repository.save(newPerson("4"))

        val stream = repository.findAll()
        stream.toList().sorted() shouldBeEqualTo listOf(homer, bart)
    }

}
