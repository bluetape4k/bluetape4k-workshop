package io.bluetape4k.workshop.jackson.annotations

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jackson.AbstractJacksonTest
import io.bluetape4k.workshop.jackson.readAs
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.RepeatedTest
import tools.jackson.module.kotlin.readValue
import java.io.Serializable

class SimpleExamples: AbstractJacksonTest() {

    companion object: KLogging()

    private data class Person(
        val name: String,
    ): Serializable

    @RepeatedTest(REPEAT_SIZE)
    fun `간단한 객체를 Json 으로 변환하기`() {
        val name = faker.name().fullName()
        val person = Person(name)

        val json = defaultMapper.writeValueAsString(person)
        log.debug { "Person: $json" }

        val document = json.toDocument()
        document.readAs<String>("$.name") shouldBeEqualTo name
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `Json 을 객체로 변환하기`() {
        val name = faker.name().fullName()
        val json = """{"name": "$name"}"""

        val person = defaultMapper.readValue<Person>(json)
        log.debug { "Person: $person" }

        person.name shouldBeEqualTo name
    }
}
