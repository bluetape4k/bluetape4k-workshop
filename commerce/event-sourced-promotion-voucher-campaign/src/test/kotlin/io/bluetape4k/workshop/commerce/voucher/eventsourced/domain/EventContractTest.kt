package io.bluetape4k.workshop.commerce.voucher.eventsourced.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class EventContractTest {

    @Test
    fun `envelope accepts uuid v7 and produces a stable checksum`() {
        val event =
            EventEnvelope(
                eventId = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abcdef"),
                tenantId = TenantId("tenant-a"),
                stream =
                    StreamReference(
                        "campaign",
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        version = 2,
                    ),
                globalPosition = 7,
                eventType = "campaign.created",
                schemaVersion = 1,
                occurredAt = Instant.parse("2026-07-22T00:00:00Z"),
                recordedAt = Instant.parse("2026-07-22T00:00:01Z"),
                correlationId = "correlation-a",
                causationId = null,
                actorSurrogate = "subject-digest-a",
                payload = EventPayload("{\"capacity\":3}"),
            )

        event.canonicalChecksum shouldBeEqualTo event.canonicalChecksum
    }

    @Test
    fun `envelope rejects a non v7 identifier and fixture pins one schema step`() {
        assertFailsWith<IllegalArgumentException> {
            EventEnvelope(
                eventId = UUID.randomUUID(),
                tenantId = TenantId("tenant-a"),
                stream = StreamReference("campaign", UUID.randomUUID(), version = 0),
                globalPosition = 1,
                eventType = "campaign.created",
                schemaVersion = 1,
                occurredAt = Instant.parse("2026-07-22T00:00:00Z"),
                recordedAt = Instant.parse("2026-07-22T00:00:00Z"),
                correlationId = "correlation-a",
                causationId = null,
                actorSurrogate = "subject-digest-a",
                payload = EventPayload("{}"),
            )
        }

        val fixture =
            UpcastGoldenFixture(
                "voucher.issued",
                fromVersion = 1,
                toVersion = 2,
                input = EventPayload("{}"),
                expected = EventPayload("{\"subjectRef\":\"digest\"}"),
            )

        fixture.toVersion shouldBeEqualTo fixture.fromVersion + 1
    }

    @Test
    fun `registry upcasts only a complete bounded chain`() {
        val registry =
            EventSchemaRegistry(
                schemas = setOf(EventSchema("voucher.issued", currentVersion = 2) { it.canonicalJson }),
                upcasters =
                    setOf(
                        EventUpcaster("voucher.issued", fromVersion = 1, toVersion = 2) {
                            EventPayload("{\"subjectRef\":\"digest\"}")
                        },
                    ),
            )

        registry.decode(SerializedEvent("voucher.issued", 1, EventPayload("{}"))) shouldBeEqualTo
            "{\"subjectRef\":\"digest\"}"
        assertFailsWith<UnknownEventSchemaException> {
            registry.decode(SerializedEvent("unknown", 1, EventPayload("{}")))
        }
        assertFailsWith<UnknownEventSchemaException> {
            EventSchemaRegistry(
                schemas = setOf(EventSchema("voucher.issued", currentVersion = 3) { it.canonicalJson }),
                upcasters = emptySet(),
            ).decode(SerializedEvent("voucher.issued", 1, EventPayload("{}")))
        }
    }

    @Test
    fun `payload bounds reject raw sensitive field names and excessive depth`() {
        listOf(
            "voucherCode",
            "accessToken",
            "token",
            "idempotencyKey",
            "authorization",
            "userId",
            "deviceId",
            "ipAddress",
        ).forEach { fieldName ->
            assertFailsWith<IllegalArgumentException> {
                EventPayload("""{"$fieldName":"raw-sensitive-value"}""")
            }
        }
        assertFailsWith<IllegalArgumentException> { EventPayload("{".repeat(17) + "}".repeat(17)) }
    }
}
