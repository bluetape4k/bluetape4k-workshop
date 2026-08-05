package io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventHashMaterial
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import org.junit.jupiter.api.Test

class CanonicalEventHashTest {
    @Test
    fun `payload property order does not change the event hash`() {
        val first = material("""{"meterCode":"api_calls","quantity":10}""")
        val reordered = material("""{"quantity":10,"meterCode":"api_calls"}""")

        CanonicalEventHash.sha256(reordered).shouldBeEqualTo(CanonicalEventHash.sha256(first))
    }

    @Test
    fun `previous hash and payload changes are detectable`() {
        val original = material("""{"quantity":10}""")

        CanonicalEventHash.sha256(original.copy(payload = """{"quantity":11}"""))
            .shouldNotBeEqualTo(CanonicalEventHash.sha256(original))
        CanonicalEventHash.sha256(original.copy(previousHash = "different"))
            .shouldNotBeEqualTo(CanonicalEventHash.sha256(original))
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
