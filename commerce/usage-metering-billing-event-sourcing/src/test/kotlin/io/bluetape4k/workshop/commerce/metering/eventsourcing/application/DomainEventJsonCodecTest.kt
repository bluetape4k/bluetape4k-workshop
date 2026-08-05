package io.bluetape4k.workshop.commerce.metering.eventsourcing.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterRegistered
import org.junit.jupiter.api.Test
import java.time.Instant

class DomainEventJsonCodecTest {
    private val codec = DomainEventJsonCodec()

    @Test
    fun `event payload is bounded before persistence`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            codec.encode(MeterRegistered("meter", "x".repeat(70_000), "USD"), Instant.EPOCH)
        }

        failure.message.shouldBeEqualTo("event_payload_too_large")
    }
}
