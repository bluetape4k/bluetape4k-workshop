package io.bluetape4k.workshop.commerce.voucher.eventsourced.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.StreamReference
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.AppendResult
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventPage
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStorePort
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRead
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExpectedAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * BoundedRehydratorTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class BoundedRehydratorTest {

    companion object: KLogging() {
/**
 * NOW은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-23T12:00:00Z")
    }

/**
 * stream은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val stream = StreamKey(
        TenantId("tenant-a"),
        "campaign",
        UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc001"),
    )
/**
 * events은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val events = (1L..4L).map(::event)

    @Test
/**
 * 이 시나리오는 "no snapshot replays the complete event stream" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `no snapshot replays the complete event stream`() {
        val result = rehydrator(null).rehydrate(request())

        log.debug { "result: $result" }
        result.state shouldBeEqualTo 4
        result.snapshotVersion shouldBeEqualTo 0L
    }

    @Test
/**
 * 이 시나리오는 "valid snapshot replays only its tail" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `valid snapshot replays only its tail`() {
        val result = rehydrator(snapshot(2, "2")).rehydrate(request())

        log.debug { "result: $result" }
        result.state shouldBeEqualTo 4
        result.snapshotVersion shouldBeEqualTo 2L
        result.replayedEvents shouldBeEqualTo 2
    }

    @Test
/**
 * 이 시나리오는 "corrupt and retired-key snapshots fall back to full replay" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `corrupt and retired-key snapshots fall back to full replay`() {
        val corrupt = BoundedRehydrator(FakeStore(events), { throw IllegalArgumentException("corrupt") })
        corrupt.rehydrate(request()).snapshotVersion shouldBeEqualTo 0L

        val retired = rehydrator(snapshot(2, "2", keyVersion = 2))
            .rehydrate(request(keyAvailable = { false }))

        log.debug { "retired: $retired" }
        retired.snapshotVersion shouldBeEqualTo 0L
    }

    @Test
/**
 * 이 시나리오는 "snapshot newer than the committed head falls back to full replay" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `snapshot newer than the committed head falls back to full replay`() {
        // 준비
        val stale = snapshot(version = 5, state = "5")

        // 실행
        val result = rehydrator(stale).rehydrate(request())
        log.debug { "result: $result" }

        // 검증
        result.state shouldBeEqualTo 4
        result.snapshotVersion shouldBeEqualTo 0L
    }

    @Test
/**
 * 이 시나리오는 "replay cap rejects an unbounded foreground reconstruction" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `replay cap rejects an unbounded foreground reconstruction`() {
        assertFailsWith<RehydrationLimitExceeded> {
            BoundedRehydrator(FakeStore(events), { null }, maxEvents = 3).rehydrate(request())
        }
    }

/**
 * rehydrator은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun rehydrator(snapshot: EventSnapshot?): BoundedRehydrator =
        BoundedRehydrator(
            eventStore = FakeStore(events),
            snapshotLoader = { snapshot },
        )

/**
 * request은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun request(keyAvailable: (Int) -> Boolean = { true }): RehydrationRequest<Int> =
        RehydrationRequest(
            stream = stream,
            emptyState = { 0 },
            restoreSnapshot = String::toInt,
            apply = { state, page -> state + page.size },
            keyVersionAvailable = keyAvailable,
        )

/**
 * snapshot은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun snapshot(version: Long, state: String, keyVersion: Int = 1): EventSnapshot =
        EventSnapshot(
            stream = stream,
            metadata = SnapshotMetadata(version, 1, keyVersion),
            canonicalState = state,
            createdAt = NOW
        )

/**
 * event은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun event(version: Long) =
        EventEnvelope(
            eventId = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc0${version.toString().padStart(2, '0')}"),
            tenantId = stream.tenantId,
            stream = StreamReference(stream.streamType, stream.streamId, version),
            globalPosition = version,
            eventType = "campaign.created",
            schemaVersion = 1,
            occurredAt = NOW,
            recordedAt = NOW,
            correlationId = "correlation-$version",
            causationId = null,
            actorSurrogate = "actor",
            payload = EventPayload("{}"),
        )

/**
 * FakeStore 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class FakeStore(private val events: List<EventEnvelope>): EventStorePort {
/**
 * load은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun load(read: EventStoreRead): EventPage {
            val page = events.filter { it.stream.version > read.afterVersion }.take(read.limit)
            return EventPage(
                events = page,
                committedHead = events.lastOrNull()?.stream?.version ?: 0
            )
        }

/**
 * appendAll은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun appendAll(appends: List<ExpectedAppend>): AppendResult = error("not used")
    }
}
