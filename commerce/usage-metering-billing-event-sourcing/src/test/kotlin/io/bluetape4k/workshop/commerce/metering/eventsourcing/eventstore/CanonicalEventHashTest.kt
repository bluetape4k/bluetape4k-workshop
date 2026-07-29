package io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore

import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventHashMaterial
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class CanonicalEventHashTest {
    @Test
    fun `payload property order does not change the event hash`() {
        val first = material("""{"meterCode":"api_calls","quantity":10}""")
        val reordered = material("""{"quantity":10,"meterCode":"api_calls"}""")

        assertEquals(CanonicalEventHash.sha256(first), CanonicalEventHash.sha256(reordered))
    }

    @Test
    fun `previous hash and payload changes are detectable`() {
        val original = material("""{"quantity":10}""")

        assertNotEquals(
            CanonicalEventHash.sha256(original),
            CanonicalEventHash.sha256(original.copy(payload = """{"quantity":11}""")),
        )
        assertNotEquals(
            CanonicalEventHash.sha256(original),
            CanonicalEventHash.sha256(original.copy(previousHash = "different")),
        )
    }

    private fun material(payload: String) = EventHashMaterial(
        stream = StreamKey("tenant-a", "Usage", "usage-1"),
        streamVersion = 1,
        eventType = "usage.accepted",
        schemaVersion = 1,
        payload = payload,
        metadata = """{"actor":"tenant-a"}""",
        previousHash = null,
    )
}
