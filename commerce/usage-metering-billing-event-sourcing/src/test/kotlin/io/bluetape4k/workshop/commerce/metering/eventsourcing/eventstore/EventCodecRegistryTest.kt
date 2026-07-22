package io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EventCodecRegistryTest {
    @Test
    fun `registry applies a contiguous upcast chain before decoding`() {
        val registry = EventCodecRegistry()
            .register("usage.accepted", 3) { payload -> payload }
            .registerUpcaster("usage.accepted", 1) { payload -> payload.replace("quantity", "amount") }
            .registerUpcaster("usage.accepted", 2) { payload -> payload.replace("amount", "quantity") }

        assertEquals("""{"quantity":10}""", registry.decode("usage.accepted", 1, """{"quantity":10}"""))
    }

    @Test
    fun `registry rejects unknown event versions`() {
        val registry = EventCodecRegistry().register("usage.accepted", 1) { it }

        assertThrows(UnknownEventSchemaException::class.java) {
            registry.decode("usage.accepted", 2, "{}")
        }
    }

    @Test
    fun `registry rejects a broken upcast chain`() {
        val registry = EventCodecRegistry()
            .register("usage.accepted", 3) { it }
            .registerUpcaster("usage.accepted", 1) { it }

        assertThrows(IllegalStateException::class.java) { registry.validate() }
    }
}
