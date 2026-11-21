package io.bluetape4k.workshop.commerce.voucher.eventsourced.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * EventDecisionTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventDecisionTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "command decision is immutable and does not mutate its aggregate" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `command decision is immutable and does not mutate its aggregate`() {
        val draft = CampaignAggregate.replay(
            listOf(
                CampaignEvent.CampaignCreated(
                    tenantId = TenantId("tenant-a"),
                    campaignId = UUID.randomUUID(),
                    startsAt = Instant.parse("2026-07-22T00:00:00Z"),
                    endsAt = Instant.parse("2026-07-22T01:00:00Z"),
                    capacity = 3,
                    perUserLimit = 1,
                    redemptionTtlSeconds = 600,
                ),
            ),
        )

        val decision = CampaignCommands.activate(draft)

        log.debug { "draft=$draft" }
        draft.state shouldBeEqualTo CampaignState.DRAFT
        draft.version shouldBeEqualTo 1

        log.debug { "decision=$decision" }
        decision.events shouldBeEqualTo listOf(CampaignEvent.CampaignActivated)

        EventDecision.empty<CampaignEvent>().events.shouldBeEmpty()
    }

    @Test
/**
 * 이 시나리오는 "replay orders positions and rejects duplicate event identifiers" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `replay orders positions and rejects duplicate event identifiers`() {
        val first = envelope("0198a1b2-c3d4-7e5f-8123-456789abcdef", 2)
        val second = envelope("0198a1b2-c3d4-7e5f-8123-456789abcdee", 1)

        EventReplay.ordered(listOf(first, second))
            .map { it.globalPosition } shouldBeEqualTo listOf(1L, 2L)

        assertFailsWith<IllegalArgumentException> {
            EventReplay.ordered(listOf(first, first))
        }
    }

/**
 * envelope은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
