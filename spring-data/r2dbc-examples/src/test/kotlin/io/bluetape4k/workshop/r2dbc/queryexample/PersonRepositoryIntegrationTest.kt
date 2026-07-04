package io.bluetape4k.workshop.r2dbc.queryexample

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.spring.data.buildExampleMatcher
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContainSame
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Example
import org.springframework.data.domain.ExampleMatcher
import org.springframework.data.domain.ExampleMatcher.GenericPropertyMatchers
import org.springframework.r2dbc.core.DatabaseClient
import java.util.*

@SpringBootTest(classes = [InfrastructureConfiguration::class])
class PersonRepositoryIntegrationTest @Autowired constructor(
    private val repository: PersonRepository,
    private val client: DatabaseClient,
) {
    companion object : KLoggingChannel()

    private var skylerFixture: Person? = null
    private var walterFixture: Person? = null
    private var flynnFixture: Person? = null
    private var marieFixture: Person? = null
    private var hankFixture: Person? = null

    private val skyler: Person get() = checkNotNull(skylerFixture) { "skyler fixture is not initialized." }
    private val walter: Person get() = checkNotNull(walterFixture) { "walter fixture is not initialized." }
    private val flynn: Person get() = checkNotNull(flynnFixture) { "flynn fixture is not initialized." }
    private val marie: Person get() = checkNotNull(marieFixture) { "marie fixture is not initialized." }
    private val hank: Person get() = checkNotNull(hankFixture) { "hank fixture is not initialized." }

    @BeforeEach
    fun beforeEach() = runSuspendIO {
        val statements = listOf(
            "DROP TABLE IF EXISTS person;",
            """
            CREATE TABLE person (
                id SERIAL PRIMARY KEY,
                firstname VARCHAR(100) NOT NULL,
                lastname VARCHAR(100) NOT NULL,
                age INTEGER NOT NULL
            );""".trimIndent()
        )

        skylerFixture = Person("Skyler", "White", 45)
        walterFixture = Person("Walter", "White", 50)
        flynnFixture = Person("Walter Jr. (Flynn)", "White", 17)
        marieFixture = Person("Marie", "Schrader", 38)
        hankFixture = Person("Hank", "Schrader", 43)

        statements.forEach { stmt ->
            client.sql(stmt).fetch().rowsUpdated().awaitSingleOrNull()
        }

        repository.saveAll(listOf(skyler, walter, flynn, marie, hank)).asFlow().collect()
    }

    @Test
    fun `context loading`() {
        client.shouldNotBeNull()
        repository.shouldNotBeNull()
    }

    @Test
    fun `count by simple example`() = runSuspendIO {

        // Kotlin 클래스에 대해서 non-null 때문에 Example 만드는 것은 이렇게 Example에 지정할 속성명을 특정해주는 [ExampleMatcher]를 사용해야 한다.
        val matcher = Person::class
            .buildExampleMatcher(Person::lastname.name)
            .withMatcher(Person::lastname.name, GenericPropertyMatchers.exact())
            .withIgnoreNullValues()

        val personExample = Person("", "White", 0)
        val example = Example.of(personExample, matcher)

        repository.count(example).awaitSingle() shouldBeEqualTo 3L
    }

    @Test
    fun `ignore properties and match by age`() = runSuspendIO {
        // Kotlin 클래스에 대해서 non-null 때문에 Example 만드는 것은 이렇게 Example에 지정할 속성명을 특정해주는 [ExampleMatcher]를 사용해야 한다.
        val matcher = Person::class
            .buildExampleMatcher(Person::age.name)
            .withMatcher(Person::age.name, GenericPropertyMatchers.exact())
            .withIgnoreNullValues()

        val example = Example.of(flynn, matcher)

        repository.findOne(example).awaitSingleOrNull() shouldBeEqualTo flynn
    }

    @Test
    fun `match starting strings ignore case`() = runSuspendIO {
        // Kotlin 클래스에 대해서 non-null 때문에 Example 만드는 것은 이렇게 Example에 지정할 속성명을 특정해주는 [ExampleMatcher]를 사용해야 한다.
        val matcher = Person::class
            .buildExampleMatcher(Person::firstname.name, Person::lastname.name)
            .withMatcher(Person::firstname.name, GenericPropertyMatchers.startsWith())
            .withMatcher(Person::lastname.name, GenericPropertyMatchers.ignoreCase())
            .withIgnoreNullValues()

        val example = Example.of(Person("Walter", "WHITE", 0), matcher)

        repository.findAll(example).asFlow().toList() shouldContainSame listOf(walter, flynn)
    }

    @Test
    fun `configuring matchers using lambdas`() = runSuspendIO {
        // Kotlin 클래스에 대해서 non-null 때문에 Example 만드는 것은 이렇게 Example에 지정할 속성명을 특정해주는 [ExampleMatcher]를 사용해야 한다.
        val matcher = ExampleMatcher.matching()
            .withIgnorePaths(Person::age.name)
            .withMatcher(Person::firstname.name, GenericPropertyMatchers.startsWith())
            .withMatcher(Person::lastname.name, GenericPropertyMatchers.ignoreCase())
            .withIgnoreNullValues()

        val example = Example.of(Person("Walter", "WHITE", 0), matcher)

        repository.findAll(example).asFlow().toList() shouldContainSame listOf(walter, flynn)
    }

    @Test
    fun `value transformer`() = runSuspendIO {
        // Kotlin 클래스에 대해서 non-null 때문에 Example 만드는 것은 이렇게 Example에 지정할 속성명을 특정해주는 [ExampleMatcher]를 사용해야 한다.
        val matcher = Person::class
            .buildExampleMatcher(Person::lastname.name, Person::age.name)
            .withMatcher(Person::age.name) { it.transform { Optional.of(50) } }
            .withIgnoreNullValues()

        val example = Example.of(Person("", "White", 99), matcher)

        repository.findOne(example).awaitSingleOrNull() shouldBeEqualTo walter
    }
}
