package io.bluetape4k.workshop.jackson.annotations

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonRawValue
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jackson.AbstractJacksonTest
import io.bluetape4k.workshop.jackson.readAs
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class RawValueExample: AbstractJacksonTest() {

    companion object: KLogging()

    class UserData() {

        constructor(name: String, json: String): this() {
            this.name = name
            this.json = json
        }

        var name: String = ""

        @field:JsonRawValue
        var json: String = ""

        @JsonProperty(value = "json")
        private fun setJsonRaw(jsonNode: JsonNode) {
            this.json = jsonNode.toString()
        }
    }

    @Test
    fun `Raw value conversion object to json`() {
        val name = faker.name().fullName()
        val json = """{"id":1, "name":"$name"}"""
        val user = UserData(name, json)

        val jsonStr = defaultMapper.writeValueAsString(user)
        log.debug { "Json=$jsonStr" }

        val doc = jsonStr.toDocument()
        doc.readAs<String>("$.name") shouldBeEqualTo name
        doc.readAs<String>("$.json.name") shouldBeEqualTo name
    }

    @Test
    fun `Raw value conversion json to object`() {
        val name = faker.name().fullName()
        val json = """{"id":1,"name":"$name"}"""
        val jsonStr = """{"name":"$name","json":$json}"""

        val userData = defaultMapper.readValue<UserData>(jsonStr)
        log.debug { "User Data=$userData" }

        userData.name shouldBeEqualTo name
        userData.json shouldBeEqualTo json
    }
}
