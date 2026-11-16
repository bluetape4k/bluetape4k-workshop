package io.bluetape4k.workshop.mongodb.examples

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.mongodb.AbstractMongodbTest
import io.bluetape4k.workshop.mongodb.domain.Person
import org.bson.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.MongoOperations
import org.springframework.data.mongodb.core.asType
import org.springframework.data.mongodb.core.createCollection
import org.springframework.data.mongodb.core.dropCollection
import org.springframework.data.mongodb.core.find
import org.springframework.data.mongodb.core.findOne
import org.springframework.data.mongodb.core.insert
import org.springframework.data.mongodb.core.query
import org.springframework.data.mongodb.core.query.Criteria.where
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.isEqualTo

class MongoTemplateExamples(
    @param:Autowired private val operations: MongoOperations,
): AbstractMongodbTest() {

    companion object: KLoggingChannel()

    @BeforeEach
    fun beforeEach() {
        operations.dropCollection<Person>()
    }

    @Test
    fun `should create collection leveraging reified type parameters`() {
        operations.createCollection<Person>()
        operations.collectionNames shouldContain "person"
    }

    @Test
    fun `should insert and find person in a fluent API style`() {
        val person = operations.insert<Person>().inCollection("person").one(newPerson())

        val query = Query.query(where(Person::firstname.name).isEqualTo(person.firstname))
        val persons = operations.query<Person>()
            .matching(query)
            .all()
            .onEach { log.debug { "person=$it" } }

        persons.size shouldBeEqualTo 1
        persons.single() shouldBeEqualTo person
    }

    @Test
    fun `should insert and project query results`() {
        val person = operations.insert<Person>().inCollection("person").one(newPerson())

        val query = Query.query(where(Person::firstname.name).isEqualTo(person.firstname))
        val people = operations.query<Person>()
            .asType<FirstnameOnly>()
            .matching(query)
            .oneValue()
            .shouldNotBeNull()

        people.getFirstname() shouldBeEqualTo person.firstname
    }

    @Test
    fun `should insert and count objects in a fluent API style`() {
        val person = operations.insert<Person>().inCollection("person").one(newPerson())

        val query = Query.query(where(Person::firstname.name).isEqualTo(person.firstname))
        val count = operations.query<Person>()
            .matching(query)
            .count()

        count shouldBeEqualTo 1
    }

    @Test
    fun `should insert and find person`() {
        val person = operations.insert(newPerson())

        val query = Query.query(where(Person::firstname.name).isEqualTo(person.firstname))
        val persons = operations.find<Person>(query).onEach { log.debug { "person=$it" } }

        persons.size shouldBeEqualTo 1
        persons.single() shouldBeEqualTo person
    }

    @Test
    fun `should apply defaulting for absent properties`() {
        val document = operations.insert<Document>()
            .inCollection("person")
            .one(Document("lastname", "White"))

        val query = Query.query(where(Person::lastname.name).isEqualTo(document["lastname"]))
        val persons = operations.query<Person>()
            .matching(query)
            .firstValue()
            .shouldNotBeNull()

        log.debug { "Load person=$persons" }
        persons.firstname shouldBeEqualTo "Walter" // Default 값을 사용합니다.
        persons.lastname shouldBeEqualTo document["lastname"]

        val walter = operations.findOne<Document>(query, "person").shouldNotBeNull()

        log.debug { "Load walter=$walter" }
        walter["lastname"] shouldBeEqualTo document["lastname"]
        walter.containsKey("_id").shouldBeTrue()
        walter.containsKey("firstname").shouldBeFalse()
    }

    interface FirstnameOnly {
        fun getFirstname(): String
    }
}
