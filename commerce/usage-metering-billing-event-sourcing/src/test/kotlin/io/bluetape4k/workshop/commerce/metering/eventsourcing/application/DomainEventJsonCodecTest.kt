package io.bluetape4k.workshop.commerce.metering.eventsourcing.application

import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterRegistered
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class DomainEventJsonCodecTest {
    private val codec = DomainEventJsonCodec()

    @Test
    fun `event payload is bounded before persistence`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            codec.encode(MeterRegistered("meter", "x".repeat(70_000), "USD"), Instant.EPOCH)
        }

        assertEquals("event_payload_too_large", failure.message)
    }
}
