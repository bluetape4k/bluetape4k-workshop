package io.bluetape4k.workshop.jackson.annotations

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fasterxml.jackson.module.kotlin.readValue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jackson.AbstractJacksonTest
import io.bluetape4k.workshop.jackson.readAs
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

/**
 * `@JsonUnwrapped` 를 이용한 객체의 필드를 풀어서 Json 으로 변환하는 예제
 */
class JsonUnwrappedExample: AbstractJacksonTest() {

    companion object: KLogging()

    private data class Address @JsonCreator constructor(
        val street: String? = null,
        val number: Int? = null,
    )

    private class Person(
        val name: String? = null,
    ) {
        /**
         * NOTE: `@JsonUnwrapped` 은 아직 생성자의 속성에 사용할 수 없다.
         */
        @JsonUnwrapped(prefix = "mainAddress_")
        var mainAddress: Address? = null

        /**
         * NOTE: `@JsonUnwrapped` 은 아직 생성자의 속성에 사용할 수 없다.
         */
        @JsonUnwrapped(prefix = "secondAddress_")
        var secondAddress: Address? = null
    }

    @Test
    fun `Flat json conversion object to json`() {
        val person = Person(
            name = "John Doe",
        ).apply {
            mainAddress = Address("Main Street", 100)
            secondAddress = Address("Second Street", 200)
        }
        val json = defaultMapper.writeValueAsString(person)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.name") shouldBeEqualTo "John Doe"
        doc.readAs<String>("$.mainAddress_street") shouldBeEqualTo "Main Street"
        doc.readAs<Int>("$.mainAddress_number") shouldBeEqualTo 100
        doc.readAs<String>("$.secondAddress_street") shouldBeEqualTo "Second Street"
        doc.readAs<Int>("$.secondAddress_number") shouldBeEqualTo 200
    }

    @Test
    fun `Flat json conversion json to object`() {
        val json = """
            {
                "name": "John Doe",
                "mainAddress_street": "Main Street",
                "mainAddress_number": 100,
                "secondAddress_street": "Second Street",
                "secondAddress_number": 200
            }
            """.trimIndent()

        val person = defaultMapper.readValue<Person>(json)

        person.name shouldBeEqualTo "John Doe"
        person.mainAddress?.street shouldBeEqualTo "Main Street"
        person.mainAddress?.number shouldBeEqualTo 100
        person.secondAddress?.street shouldBeEqualTo "Second Street"
        person.secondAddress?.number shouldBeEqualTo 200
    }
}
