package io.bluetape4k.workshop.jackson.annotations

import com.fasterxml.jackson.annotation.JsonGetter
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.module.kotlin.readValue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jackson.AbstractJacksonTest
import io.bluetape4k.workshop.jackson.readAs
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class RenameExample: AbstractJacksonTest() {

    companion object: KLogging()

    private class User {
        var name: String = ""

        @field:JsonSetter("code")
        var externalCode: String = ""

        var userCode: String = ""
            @JsonGetter("publicCode")
            get
            @JsonSetter("userCode")
            set

        @field:JsonProperty("internalCode")
        var code: String = ""
    }

    @Test
    fun `rename fileds conversion object to json`() {
        val user = User().apply {
            name = "John Doe"
            externalCode = "external"
            userCode = "user"
            code = "code"
        }

        val json = defaultMapper.writeValueAsString(user)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.name") shouldBeEqualTo "John Doe"
        doc.readAs<String>("$.externalCode") shouldBeEqualTo "external"
        doc.readAs<String>("$.publicCode") shouldBeEqualTo "user"
        doc.readAs<String>("$.internalCode") shouldBeEqualTo "code"
    }

    @Test
    fun `rename fields conversion json to object`() {
        val json = """
            {
              "name": "John Doe",
              "code": "external",
              "userCode": "user",
              "internalCode": "code"
            }
        """.trimIndent()

        val user = defaultMapper.readValue<User>(json)
        log.debug { "User=$user" }

        user.name shouldBeEqualTo "John Doe"
        user.externalCode shouldBeEqualTo "external"
        user.userCode shouldBeEqualTo "user"
        user.code shouldBeEqualTo "code"
    }
}
