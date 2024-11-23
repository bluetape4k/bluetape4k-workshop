package io.bluetape4k.workshop.jackson.annotations

import com.fasterxml.jackson.annotation.JsonView
import com.fasterxml.jackson.databind.ObjectWriter
import com.fasterxml.jackson.databind.SerializationFeature
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.jackson.AbstractJacksonTest
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.Serializable

class JsonViewExample: AbstractJacksonTest() {

    companion object: KLogging()

    private val publicViewWriter: ObjectWriter = defaultMapper.copy().let {
        it.enable(SerializationFeature.INDENT_OUTPUT)
        it.writerWithView(ViewScope.Public::class.java)
    }

    private val internalViewWriter: ObjectWriter = defaultMapper.copy().let {
        it.enable(SerializationFeature.INDENT_OUTPUT)
        it.writerWithView(ViewScope.Internal::class.java)
    }

    private val user = User(1, "John Doe", "john.doe@example.com")

    @Test
    fun `Public View 를 적용한 Writer 를 이용하여 Json 변환`() {
        // Public View
        val publicJson = publicViewWriter.writeValueAsString(user)
        log.debug { "Public View: $publicJson" }
        publicJson shouldNotContain "email"
    }

    @Test
    fun `Internal View 를 가진 Writer 를 이용하여 Json 변환`() {
        // Internal View
        val internalViewJson = internalViewWriter.writeValueAsString(user)
        log.debug { "Internal View: $internalViewJson" }
        internalViewJson shouldContain "email"
    }

    interface ViewScope {
        interface Public
        interface Internal: Public
    }

    data class User(

        @get:JsonView(ViewScope.Public::class)
        val id: Long,

        @get:JsonView(ViewScope.Public::class)
        val name: String,

        @get:JsonView(ViewScope.Internal::class)
        val email: String,
    ): Serializable
}
