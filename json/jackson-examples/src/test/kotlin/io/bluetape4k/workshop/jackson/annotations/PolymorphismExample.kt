package io.bluetape4k.workshop.jackson.annotations

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jackson.AbstractJacksonTest
import io.bluetape4k.workshop.jackson.readAs
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

/**
 * `@JsonTypeInfo`, `@JsonSubTypes` 를 이용한 다형성 예제
 */
class PolymorphismExample: AbstractJacksonTest() {

    companion object: KLogging()

    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type",
    )
    @JsonSubTypes(
        JsonSubTypes.Type(value = Person::class, name = "person"),
        JsonSubTypes.Type(value = Student::class, name = "student"),
        JsonSubTypes.Type(value = Employee::class, name = "employee"),
    )
    private open class Person(var name: String)

    @JsonSubTypes(
        JsonSubTypes.Type(value = Student::class, name = "student"),
        JsonSubTypes.Type(value = ExchangeStudent::class, name = "exchangeStudent"),
    )
    private open class Student(
        name: String,
        var school: String,
    ): Person(name)

    private open class ExchangeStudent(
        name: String,
        university: String,
        var country: String,
    ): Student(name, university)

    private open class Employee(
        name: String,
        var company: String,
    ): Person(name)

    @Test
    fun `다형성을 가진 객체를 Json 으로 변환`() {
        val person = Person("John Doe")
        val json = defaultMapper.writeValueAsString(person)
        log.debug { "Person: $json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.type") shouldBeEqualTo "person"
        doc.readAs<String>("$.name") shouldBeEqualTo "John Doe"
    }
}
