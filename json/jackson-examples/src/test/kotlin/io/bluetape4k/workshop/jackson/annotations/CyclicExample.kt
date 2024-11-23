package io.bluetape4k.workshop.jackson.annotations

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jackson.AbstractJacksonTest
import io.bluetape4k.workshop.jackson.readAs
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Test

class CyclicExample: AbstractJacksonTest() {

    companion object: KLogging()

    private class User {
        companion object {
            @JvmStatic
            operator fun invoke(name: String, contactData: ContactData): User {
                return User().apply {
                    this.name = name
                    contactData.user = this
                    this.contactData = contactData
                }
            }
        }

        var name: String = ""

        @JsonIgnoreProperties("user")
        var contactData: ContactData? = null


        data class ContactData(
            var phone: String,
            @JsonIgnoreProperties("contactData", allowSetters = true)  // allowSetters is required for deserialization
            var user: User? = null,
        )
    }

    @Test
    fun `cyclic relation conversion object to json`() {
        val user = User("John Doe", User.ContactData("555-555-555"))

        val json = jacksonObjectMapper().writeValueAsString(user)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.name") shouldBeEqualTo "John Doe"
        doc.readAs<String>("$.contactData.phone") shouldBeEqualTo "555-555-555"
        doc.readAs<Any>("$.contactData.user").shouldBeNull()
    }

    @Test
    fun `cyclic relation conversion object to json by child element`() {
        val user = User("John Doe", User.ContactData("555-555-555"))

        val json = jacksonObjectMapper().writeValueAsString(user.contactData)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.phone") shouldBeEqualTo "555-555-555"
        doc.readAs<String>("$.user.name") shouldBeEqualTo "John Doe"
        doc.readAs<Any>("$.user.contactData").shouldBeNull()
    }

    @Test
    fun `cyclic relation conversion json to object`() {
        val json = """
            {
              "name": "John Doe",
              "contactData": {
                "phone": "555-555-555",
              }
            }
            """.trimIndent()

        val user = defaultMapper.readValue<User>(json)
        user.name shouldBeEqualTo "John Doe"
        user.contactData?.phone shouldBeEqualTo "555-555-555"
        user.contactData?.user.shouldBeNull()
    }

    @Test
    fun `cyclic relation convertion json to object by child element`() {
        val json = """
            {
              "phone": "555-555-555",
              "user": {
                "name": "John Doe"
              }
            }
            """.trimIndent()

        val contactData = defaultMapper.readValue<User.ContactData>(json)
        contactData.phone shouldBeEqualTo "555-555-555"
        contactData.user?.name shouldBeEqualTo "John Doe"
        contactData.user?.contactData.shouldBeNull()
    }
}
