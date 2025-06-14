package io.bluetape4k.workshop.mongodb.reactive

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.mongodb.AbstractMongodbTest
import io.bluetape4k.workshop.mongodb.domain.Person
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.CollectionOptions
import org.springframework.data.mongodb.core.ReactiveMongoOperations
import org.springframework.data.mongodb.core.collectionExists
import org.springframework.data.mongodb.core.createCollection
import org.springframework.data.mongodb.core.dropCollection
import reactor.core.publisher.Mono

abstract class AbstractReactiveMongoTest(
    @Autowired private val operations: ReactiveMongoOperations,
): AbstractMongodbTest() {

    companion object: KLoggingChannel()

    @BeforeEach
    fun beforeEach() {
        runBlocking(Dispatchers.IO) {
            // NOTE: [@Tailable] 을 지원하려면 Collection이 capped 인 놈만 가능합니다.
            //
            val collectionOptions = CollectionOptions.empty()
                .size(1024 * 1024L)
                .maxDocuments(1000)
                .capped()

            val collection = operations.collectionExists<Person>()
                .flatMap { exists ->
                    if (exists) operations.dropCollection<Person>()
                    else Mono.empty()
                }
                .then(operations.createCollection<Person>(collectionOptions))

            val documents = collection.awaitSingle()
            documents.countDocuments().awaitSingle() shouldBeEqualTo 0

            val persons = listOf(
                Person("Walter", "White", 50),
                Person("Skyler", "White", 45),
                Person("Saul", "Goodman", 42),
                Person("Jesse", "Pinkman", 27)
            )

            val insertedPersons = operations.insertAll(persons).asFlow().toList()
            insertedPersons shouldHaveSize 4
        }
    }
}
