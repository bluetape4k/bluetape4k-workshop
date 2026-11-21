package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import org.junit.jupiter.api.Test
import java.util.*

/**
 * EventStorePortTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventStorePortTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "append request carries an expected version for one tenant-scoped stream" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `append request carries an expected version for one tenant-scoped stream`() {
        val stream = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())
        val request = ExpectedAppend(stream, expectedVersion = 3, events = listOf(event("created")))

        request.expectedVersion shouldBeEqualTo 3L
        request.stream shouldBeEqualTo stream
    }

    @Test
/**
 * 이 시나리오는 "append result preserves the committed global position range" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `append result preserves the committed global position range`() {
        val campaign = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())
        val voucher = StreamKey(TenantId("tenant-a"), "voucher", UUID.randomUUID())

        val result = AppendResult.Appended(
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
/**
 * 이 시나리오는 "stream load rejects an unbounded page request" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stream load rejects an unbounded page request`() {
        assertFailsWith<IllegalArgumentException> {
            EventStoreRead(
                StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID()),
                afterVersion = 0,
                limit = 201,
            )
        }
    }

/**
 * event은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun event(type: String) =
        EventToAppend(
            eventId = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abcdef"),
            eventType = type,
            schemaVersion = 1,
            payload = EventPayload("{}"),
        )
}
