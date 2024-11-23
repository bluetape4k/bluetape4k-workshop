package io.bluetape4k.workshop.jackson.annotations

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.module.kotlin.readValue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jackson.AbstractJacksonTest
import io.bluetape4k.workshop.jackson.readAs
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.RepeatedTest

class IgnoreExample: AbstractJacksonTest() {

    companion object: KLogging()

    private data class Friend(
        val name: String,

        @JsonIgnore
        val secret: String? = null,     // 속성에 값이 없을 수 있으므로, 기본 값은 null로 설정
    )

    @RepeatedTest(REPEAT_SIZE)
    fun `Ignore field conversion object to json`() {
        val name = faker.name().fullName()
        val field = Friend(name, faker.lorem().word())

        val json = defaultMapper.writeValueAsString(field)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.name") shouldBeEqualTo name
        doc.readAs<String>("$.secret").shouldBeNull()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `Ignore field conversion json to object`() {
        val name = faker.name().fullName()
        val secret = faker.lorem().word()
        val json = """
            {
                "name": "$name",
                "secret": "$secret"
            }
            """.trimIndent()

        val field = defaultMapper.readValue<Friend>(json)
        log.debug { "Field=$field" }

        field.name shouldBeEqualTo name
        field.secret.shouldBeNull()
    }
}
