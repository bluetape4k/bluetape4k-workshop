package io.bluetape4k.workshop.jackson.annotations

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import io.bluetape4k.jackson.writeAsString
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jackson.AbstractJacksonTest
import io.bluetape4k.workshop.jackson.readAs
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.RepeatedTest

class RootValueExample: AbstractJacksonTest() {

    companion object: KLogging()

    private data class User(
        val id: Int?,
        val name: String?,
    )

    /**
     * 클래스 명이 루트로 감싸진 JSON을 생성한다.
     */
    private val mapper: ObjectMapper get() = defaultMapper.copy()

    @RepeatedTest(REPEAT_SIZE)
    fun `Root conversion object to json`() {
        val name = faker.name().fullName()
        val user = User(1, name)

        val json = mapper.enable(SerializationFeature.WRAP_ROOT_VALUE).writeAsString(user)!!
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.User.name") shouldBeEqualTo name
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `Root conversion json to object`() {
        val name = faker.name().fullName()
        val json = """{"User":{"id":1, "name":"$name"}}"""

        val user = mapper.enable(DeserializationFeature.UNWRAP_ROOT_VALUE).readValue<User>(json)
        log.debug { "User=$user" }

        user.id shouldBeEqualTo 1
        user.name shouldBeEqualTo name
    }
}
