package io.bluetape4k.workshop.messaging.fallback.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.workshop.messaging.fallback.domain.OrderStatus
import io.bluetape4k.workshop.messaging.fallback.publication.OrderPlacedEvent
import org.junit.jupiter.api.Test
import tools.jackson.core.JacksonException
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.DeserializationFeature
import java.time.LocalDateTime
import kotlin.test.assertFailsWith

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
    fun `object mapper preserves the order placed wire payload`() {
        val event = OrderPlacedEvent(
            orderId = 7L,
            customerId = "고객🙂",
            product = "상품",
            quantity = 2,
            status = OrderStatus.PENDING,
            createdAt = LocalDateTime.of(2026, 9, 8, 12, 34, 56),
        )

        KafkaConfig().objectMapper().writeValueAsString(event) shouldBeEqualTo
            """{"orderId":7,"customerId":"고객🙂","product":"상품","quantity":2,"status":"PENDING","createdAt":"2026-09-08T12:34:56","eventId":"order-placed:7:v1"}"""
    }

    private data class Payload(val value: String)
}
