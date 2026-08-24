package io.bluetape4k.workshop.optimization.lastmile.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Instant

class LastMileEventCanonicalizerTest {

    @Test
    fun `same canonical payload is duplicate regardless of map order`() {
        val first = event(mapOf("jobId" to "job-1", "window" to "morning"))
        val second = event(mapOf("window" to "morning", "jobId" to "job-1"))

        second.digest shouldBeEqualTo first.digest
        LastMileEventCanonicalizer.compare(first, second) shouldBeEqualTo EventDigestMatch.DUPLICATE
    }

    @Test
    fun `same event key with changed payload is a digest conflict`() {
        val first = event(mapOf("jobId" to "job-1"))
        val second = event(mapOf("jobId" to "job-2"))

        LastMileEventCanonicalizer.compare(first, second) shouldBeEqualTo EventDigestMatch.DIGEST_CONFLICT
    }

    @Test
    fun `oversized event payload is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            event(mapOf("payload" to "x".repeat(LastMileLimits.MAX_EVENT_PAYLOAD)))
        }
    }

    private fun event(payload: Map<String, String>): LastMileEvent {
        val canonical = LastMileEventCanonicalizer.canonicalize(
            type = LastMileEventType.PICKUP_WINDOW_CHANGED,
            aggregateId = "job-1",
            eventKey = "window-1",
            payload = payload,
        )
        return LastMileEvent(
            eventId = EventId("event-1"),
            type = LastMileEventType.PICKUP_WINDOW_CHANGED,
            aggregateId = "job-1",
            eventKey = "window-1",
            occurredAt = Instant.parse("2026-08-24T08:00:00Z"),
            canonicalPayload = canonical,
        )
    }
}
