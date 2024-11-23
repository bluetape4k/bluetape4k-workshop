package io.bluetape4k.workshop.jackson.annotations

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import io.bluetape4k.jackson.writeAsString
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jackson.AbstractJacksonTest
import io.bluetape4k.workshop.jackson.readAs
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.RepeatedTest

class OnewayExample: AbstractJacksonTest() {

    companion object: KLogging()

    private data class User(
        val name: String,

        /**
         * JsonProperty.Access.WRITE_ONLY 를 사용하면, 해당 필드는 Json 으로 변환할 때는 포함되지 않는다
         * WRITE_ONLY 보다는 SET 이 이해하기 쉽다. (getter/setter 중 set 만 가능)
         */
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        val password: String,

        /**
         * JsonProperty.Access.READ_ONLY 를 사용하면, 해당 필드는 Json 으로 변환할 때는 포함되지 않는다
         * READ_ONLY 보다는 GET 이 이해하기 쉽다. (getter/setter 중 get 만 가능)
         */
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        val identifier: Int?,
    )

    @RepeatedTest(REPEAT_SIZE)
    fun `Oneway conversion object to json`() {
        val name = faker.name().fullName()
        val password = faker.internet().password()
        val user = User(name, password, 100)

        val json = defaultMapper.writeAsString(user)!!
        log.debug { "Json=$json" }

        val doc = json.toDocument()

        doc.readAs<String>("$.name") shouldBeEqualTo name
        doc.readAs<String>("$.password").shouldBeNull()     // Json 으로 변환할 때는 포함되지 않음
        doc.readAs<Int>("$.identifier") shouldBeEqualTo 100
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `Oneway conversion json to object`() {
        val name = faker.name().fullName()
        val password = faker.internet().password()
        val json = """{"name":"$name", "password":"$password", "identifier":100}"""

        val user = defaultMapper.readValue<User>(json)
        log.debug { "User=$user" }

        user.name shouldBeEqualTo name
        user.password shouldBeEqualTo password
        user.identifier.shouldBeNull()          // Json 을 객체로 변환할 때는 포함되지 않음
    }
}
