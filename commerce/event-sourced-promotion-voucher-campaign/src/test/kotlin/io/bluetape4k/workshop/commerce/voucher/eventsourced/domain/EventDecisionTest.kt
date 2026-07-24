package io.bluetape4k.workshop.commerce.voucher.eventsourced.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class EventDecisionTest {

    @Test
    fun `command decision is immutable and does not mutate its aggregate`() {
        val draft =
            CampaignAggregate.replay(
                listOf(
                    CampaignEvent.CampaignCreated(
                        TenantId("tenant-a"),
                        UUID.randomUUID(),
                        Instant.parse("2026-07-22T00:00:00Z"),
                        Instant.parse("2026-07-22T01:00:00Z"),
                        3,
                        1,
                        600,
                    ),
                ),
            )

        val decision = CampaignCommands.activate(draft)

        draft.state shouldBeEqualTo CampaignState.DRAFT
        draft.version shouldBeEqualTo 1
        decision.events shouldBeEqualTo listOf(CampaignEvent.CampaignActivated)
        EventDecision.empty<CampaignEvent>().events shouldBeEqualTo emptyList()
    }

    @Test
    fun `replay orders positions and rejects duplicate event identifiers`() {
        val first = envelope("0198a1b2-c3d4-7e5f-8123-456789abcdef", 2)
        val second = envelope("0198a1b2-c3d4-7e5f-8123-456789abcdee", 1)

        EventReplay.ordered(listOf(first, second)).map { it.globalPosition } shouldBeEqualTo
            listOf(1L, 2L)
        assertFailsWith<IllegalArgumentException> { EventReplay.ordered(listOf(first, first)) }
    }

    private fun envelope(eventId: String, globalPosition: Long): EventEnvelope =
        EventEnvelope(
            eventId = UUID.fromString(eventId),
            tenantId = TenantId("tenant-a"),
            stream = StreamReference("campaign", UUID.randomUUID(), globalPosition),
            globalPosition = globalPosition,
            eventType = "campaign.created",
            schemaVersion = 1,
            occurredAt = Instant.parse("2026-07-22T00:00:00Z"),
            recordedAt = Instant.parse("2026-07-22T00:00:00Z"),
            correlationId = "correlation",
            causationId = null,
            actorSurrogate = "actor",
            payload = EventPayload("{}"),
        )
}
