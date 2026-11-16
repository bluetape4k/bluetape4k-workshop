package io.bluetape4k.workshop.commerce.metering.eventsourcing.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterRegistered
import org.junit.jupiter.api.Test
import java.time.Instant

class DomainEventJsonCodecTest {

    companion object: KLogging()

    private val codec = DomainEventJsonCodec()

    @Test
    fun `event payload is bounded before persistence`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            val event = MeterRegistered("meter", "x".repeat(70_000), "USD")
            codec.encode(event, Instant.EPOCH)
        }

        log.debug { "failure: $failure" }
        failure.message shouldBeEqualTo "event_payload_too_large"
    }

    @Test
    fun `default event identity uses uuid v7`() {
        val event = MeterRegistered("meter", "api-calls", "USD")
        val newEvent = codec.encode(event, Instant.EPOCH)

        log.debug { "newEvent=$newEvent" }
        newEvent.eventId.version() shouldBeEqualTo 7
    }
}
