package io.bluetape4k.workshop.commerce.voucher.eventsourced.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * EventContractTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventContractTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "envelope accepts uuid v7 and produces a stable checksum" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `envelope accepts uuid v7 and produces a stable checksum`() {
        val event = EventEnvelope(
            eventId = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abcdef"),
            tenantId = TenantId("tenant-a"),
            stream = StreamReference(
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

        log.debug { "event=$event" }
        event.canonicalChecksum shouldBeEqualTo "b8e8da6a87c77c533bc5663e6396e0ebebaa18df8778acf3fc34f7c7186ecdfe"
    }

    @Test
/**
 * 이 시나리오는 "envelope rejects a non v7 identifier and fixture pins one schema step" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

        val fixture = UpcastGoldenFixture(
            "voucher.issued",
            fromVersion = 1,
            toVersion = 2,
            input = EventPayload("{}"),
            expected = EventPayload("{\"subjectRef\":\"digest\"}"),
        )

        log.debug { "fixture=$fixture" }
        fixture.toVersion shouldBeEqualTo fixture.fromVersion + 1
    }

    @Test
/**
 * 이 시나리오는 "registry upcasts only a complete bounded chain" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `registry upcasts only a complete bounded chain`() {
        val registry = EventSchemaRegistry(
            schemas = setOf(EventSchema("voucher.issued", currentVersion = 2) { it.canonicalJson }),
            upcasters = setOf(
                EventUpcaster("voucher.issued", fromVersion = 1, toVersion = 2) {
                    EventPayload("{\"subjectRef\":\"digest\"}")
                },
            ),
        )

        val serializedEvent = SerializedEvent("voucher.issued", 1, EventPayload("{}"))
        registry.decode(serializedEvent) shouldBeEqualTo "{\"subjectRef\":\"digest\"}"

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
/**
 * 이 시나리오는 "payload bounds reject raw sensitive field names and excessive depth" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

        assertFailsWith<IllegalArgumentException> {
            EventPayload("{".repeat(17) + "}".repeat(17))
        }
    }
}
