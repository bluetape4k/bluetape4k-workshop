package io.bluetape4k.workshop.messaging.outbox.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.jackson3.Jackson
import org.junit.jupiter.api.Test
import tools.jackson.core.JacksonException
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.DeserializationFeature

class KafkaConfigTest {

    @Test
    fun `object mapper reuses the bluetape baseline without changing the legacy input contract`() {
        val mapper = KafkaConfig().objectMapper()

        (mapper !== Jackson.defaultJsonMapper).shouldBeTrue()
        mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).shouldBeFalse()
        mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).shouldBeTrue()
        JsonReadFeature.ALLOW_TRAILING_COMMA
            .enabledIn(mapper.tokenStreamFactory().getFormatReadFeatures())
            .shouldBeFalse()
        mapper.readValue("""{"value":"ok","unknown":true}""", Payload::class.java).value shouldBeEqualTo "ok"
        assertFailsWith<JacksonException> {
            mapper.readValue("""{"value":"ok"},{}""", Payload::class.java)
        }
        assertFailsWith<JacksonException> {
            mapper.readValue("""{"value":"ok",}""", Payload::class.java)
        }
    }

    @Test
    fun `object mapper preserves the outbox wire payload`() {
        val payload = linkedMapOf(
            "orderId" to 7L,
            "customerId" to "고객🙂",
            "product" to "상품",
            "quantity" to 2,
            "status" to "PENDING",
        )

        KafkaConfig().objectMapper().writeValueAsString(payload) shouldBeEqualTo
            """{"orderId":7,"customerId":"고객🙂","product":"상품","quantity":2,"status":"PENDING"}"""
    }

    private data class Payload(val value: String)
}
