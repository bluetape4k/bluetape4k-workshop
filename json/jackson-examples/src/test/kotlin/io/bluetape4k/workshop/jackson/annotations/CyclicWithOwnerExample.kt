package io.bluetape4k.workshop.jackson.annotations

import com.fasterxml.jackson.annotation.JsonBackReference
import com.fasterxml.jackson.annotation.JsonManagedReference
import com.fasterxml.jackson.module.kotlin.readValue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jackson.AbstractJacksonTest
import io.bluetape4k.workshop.jackson.readAs
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Test

/**
 * `@JsonManagedReference` 를 이용하여 순환 참조를 해결하는 예제
 */
class CyclicWithOwnerExample: AbstractJacksonTest() {

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

        @JsonManagedReference
        var contactData: ContactData? = null

        class ContactData() {
            constructor(phone: String): this() {
                this.phone = phone
            }

            var phone: String = ""

            @JsonBackReference
            var user: User? = null
        }
    }

    @Test
    fun `Cyclic relation with owner conversion object to json`() {
        val user = User("John Doe", User.ContactData("555-555-555"))
        val json = defaultMapper.writeValueAsString(user)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.name") shouldBeEqualTo "John Doe"
        doc.readAs<String>("$.contactData.phone") shouldBeEqualTo "555-555-555"
        doc.readAs<User>("$.contactData.user").shouldBeNull()
    }

    @Test
    fun `Cyclic relation with owner conversion object to json by child element`() {
        val user = User("John Doe", User.ContactData("555-555-555"))
        val json = defaultMapper.writeValueAsString(user.contactData)
        log.debug { "Json=$json" }

        val doc = json.toDocument()
        doc.readAs<String>("$.phone") shouldBeEqualTo "555-555-555"
        doc.readAs<User>("$.user").shouldBeNull()
    }

    @Test
    fun `Cyclic relation with owner conversion json to object`() {
        val json = """
            {
              "name": "John Doe",
              "contactData": {
                "phone": "555-555-555"
              }
            }
        """.trimIndent()

        val user = defaultMapper.readValue<User>(json)
        user.name shouldBeEqualTo "John Doe"
        user.contactData?.phone shouldBeEqualTo "555-555-555"
        user.contactData?.user shouldBeEqualTo user
    }

    @Test
    fun `Cyclic relation with owner conversion json to object by child element`() {
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
