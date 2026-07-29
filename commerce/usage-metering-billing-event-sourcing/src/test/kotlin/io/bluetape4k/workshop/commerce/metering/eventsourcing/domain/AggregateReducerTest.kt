package io.bluetape4k.workshop.commerce.metering.eventsourcing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class AggregateReducerTest {
    @Test
    fun `meter reducer deterministically selects the price effective at usage time`() {
        val registered = MeterRegistered("api_calls", "request", "USD")
        val first = PriceActivated("USD", BigDecimal("0.10"), Instant.parse("2026-06-01T00:00:00Z"))
        val second = PriceActivated("USD", BigDecimal("0.20"), Instant.parse("2026-07-01T00:00:00Z"))

        val state = listOf(registered, first, second).fold(MeterState.Empty, MeterReducer::evolve)

        assertEquals(BigDecimal("0.10"), state.priceAt(Instant.parse("2026-06-30T23:59:59Z")).unitPrice)
        assertEquals(BigDecimal("0.20"), state.priceAt(Instant.parse("2026-07-01T00:00:00Z")).unitPrice)
    }

    @Test
    fun `meter cannot register twice`() {
        val registered = MeterReducer.evolve(MeterState.Empty, MeterRegistered("api_calls", "request", "USD"))

        assertThrows(IllegalStateException::class.java) {
            MeterReducer.evolve(registered, MeterRegistered("api_calls", "request", "USD"))
        }
    }
}
