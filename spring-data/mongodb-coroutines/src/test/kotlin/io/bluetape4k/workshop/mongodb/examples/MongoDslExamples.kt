package io.bluetape4k.workshop.mongodb.examples

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.mongodb.AbstractMongodbTest
import io.bluetape4k.workshop.mongodb.domain.Person
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.MongoOperations
import org.springframework.data.mongodb.core.dropCollection
import org.springframework.data.mongodb.core.find
import org.springframework.data.mongodb.core.insert
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.data.mongodb.core.query.regex

class MongoDslExamples(
    @param:Autowired private val operations: MongoOperations,
): AbstractMongodbTest() {

    companion object: KLoggingChannel()

    @BeforeEach
    fun beforeEach() {
        operations.dropCollection<Person>()
    }

    @Test
    fun `simple type-safe find`() {
        val person1 = operations.insert(newPerson())
        val person2 = operations.insert(newPerson())

        val person2Firstname = person2.firstname.shouldNotBeNull()
        val query = Query(Person::firstname isEqualTo person2Firstname)
        val persons = operations.find<Person>(query).onEach { log.debug { "person=$it" } }

        persons shouldHaveSize 1
        persons.single() shouldBeEqualTo person2

        val person1Firstname = person1.firstname.shouldNotBeNull()
        val query2 = Query(Person::firstname isEqualTo person1Firstname)
        val persons2 = operations.find<Person>(query2).onEach { log.debug { "person2=$it" } }

        persons2 shouldHaveSize 1
        persons2.single() shouldBeEqualTo person1
    }

    @Test
    fun `more complex type-safe find`() {
        val person1 = operations.insert<Person>().one(newPerson())
        val person2 = operations.insert<Person>().one(newPerson().copy(firstname = "Sunghyouk"))

        val criteria = Criteria().andOperator(
            Person::lastname isEqualTo person2.lastname.shouldNotBeNull(),
            Person::firstname regex "^Sun.*"
        )
        val persons = operations.find<Person>(Query(criteria))
            .onEach { log.debug { "person=$it" } }

        persons shouldHaveSize 1
        persons.single() shouldBeEqualTo person2 shouldNotBeEqualTo person1
    }
}
