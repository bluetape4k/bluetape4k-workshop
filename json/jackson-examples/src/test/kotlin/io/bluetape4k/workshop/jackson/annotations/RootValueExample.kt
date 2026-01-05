package io.bluetape4k.workshop.jackson.annotations


import io.bluetape4k.jackson3.writeAsString
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jackson.AbstractJacksonTest
import io.bluetape4k.workshop.jackson.readAs
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.RepeatedTest
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

class RootValueExample: AbstractJacksonTest() {

    companion object: KLogging()

    private data class User(
        val id: Int?,
        val name: String?,
    )

    /**
     * 클래스 명이 루트로 감싸진 JSON을 생성한다.
     */
    private val mapper: JsonMapper get() = defaultMapper

    @RepeatedTest(REPEAT_SIZE)
    fun `Root conversion object to json`() {
        val name = faker.name().fullName()
        val user = User(1, name)

        val json = mapper.rebuild().apply {
            enable(SerializationFeature.WRAP_ROOT_VALUE)
        }
            .build()
            .writeAsString(user)!!

        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.User.name") shouldBeEqualTo name
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `Root conversion json to object`() {
        val name = faker.name().fullName()
        val json = """{"User":{"id":1, "name":"$name"}}"""

        val user = mapper.rebuild()
            .apply {
                enable(DeserializationFeature.UNWRAP_ROOT_VALUE)
            }
            .build()
            .readValue<User>(json)

        log.debug { "User=$user" }

        user.id shouldBeEqualTo 1
        user.name shouldBeEqualTo name
    }
}
