package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import org.junit.jupiter.api.Test
import java.util.UUID

internal class EventStorePortTest {

    @Test
    fun `append request carries an expected version for one tenant-scoped stream`() {
        val stream = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())
        val request = ExpectedAppend(stream, expectedVersion = 3, events = listOf(event("created")))

        (request.expectedVersion == 3L).shouldBeTrue()
        request.stream shouldBeEqualTo stream
    }

    @Test
    fun `append result preserves the committed global position range`() {
        val campaign = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())
        val voucher = StreamKey(TenantId("tenant-a"), "voucher", UUID.randomUUID())

        val result =
            AppendResult.Appended(
                finalVersions = mapOf(campaign to 4, voucher to 8),
                firstGlobalPosition = 41,
                lastGlobalPosition = 43,
            )

        result.finalVersions[campaign] shouldBeEqualTo 4L
        result.finalVersions[voucher] shouldBeEqualTo 8L
        result.firstGlobalPosition shouldBeEqualTo 41L
        result.lastGlobalPosition shouldBeEqualTo 43L
    }

    @Test
    fun `stream load rejects an unbounded page request`() {
        assertFailsWith<IllegalArgumentException> {
            EventStoreRead(
                StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID()),
                afterVersion = 0,
                limit = 201,
            )
        }
    }

    private fun event(type: String) =
        EventToAppend(
            eventId = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abcdef"),
            eventType = type,
            schemaVersion = 1,
            payload = EventPayload("{}"),
        )
}
