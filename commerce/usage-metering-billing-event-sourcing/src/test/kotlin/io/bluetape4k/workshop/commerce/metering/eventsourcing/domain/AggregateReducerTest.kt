package io.bluetape4k.workshop.commerce.metering.eventsourcing.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class AggregateReducerTest {

    companion object: KLogging()

    @Test
    fun `meter reducer deterministically selects the price effective at usage time`() {
        val registered = MeterRegistered("api_calls", "request", "USD")
        val first = PriceActivated("USD", BigDecimal("0.10"), Instant.parse("2026-06-01T00:00:00Z"))
        val second = PriceActivated("USD", BigDecimal("0.20"), Instant.parse("2026-07-01T00:00:00Z"))

        val state = listOf(registered, first, second)
            .fold(MeterState.Empty, MeterReducer::evolve)

        log.debug { "state: $state" }
        state.priceAt(Instant.parse("2026-06-30T23:59:59Z")).unitPrice shouldBeEqualTo BigDecimal("0.10")
        state.priceAt(Instant.parse("2026-07-01T00:00:00Z")).unitPrice shouldBeEqualTo BigDecimal("0.20")
    }

    @Test
    fun `meter cannot register twice`() {
        val registered = MeterReducer.evolve(
            state = MeterState.Empty,
            event = MeterRegistered("api_calls", "request", "USD")
        )
        log.debug { "registered: $registered" }

        assertFailsWith<IllegalStateException> {
            MeterReducer.evolve(
                state = registered,
                event = MeterRegistered("api_calls", "request", "USD")
            )
        }
    }
}
