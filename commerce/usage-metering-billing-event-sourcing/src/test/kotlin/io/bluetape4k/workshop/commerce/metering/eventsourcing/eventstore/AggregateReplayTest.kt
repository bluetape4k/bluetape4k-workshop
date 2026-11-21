package io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventHashMaterial
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterRegistered
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PriceActivated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * AggregateReplayTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class AggregateReplayTest {

    companion object: KLogging()

/**
 * stream은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val stream = StreamKey("tenant-a", "Meter", "api_calls")

/**
 * registry은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val registry = EventCodecRegistry()
        .register("meter.registered", 1) {
            MeterRegistered(
                meterCode = "api_calls",
                unit = "request",
                currency = "USD"
            )
        }
        .register("price.activated", 1) {
            PriceActivated(
                currency = "USD",
                unitPrice = BigDecimal("0.10"),
                effectiveFrom = Instant.parse("2026-06-01T00:00:00Z")
            )
        }
/**
 * policy은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val policy = ReplayPolicy(registry, 1)

    @Test
/**
 * 이 시나리오는 "valid snapshot replays only its tail to the same state" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `valid snapshot replays only its tail to the same state`() {
        val events = events()
        val afterFirst = MeterReducer.evolve(MeterState.Empty, MeterRegistered("api_calls", "request", "USD"))
        val snapshot = AggregateSnapshotSeed(afterFirst, 1, events.first().eventHash, 1)

        val full = AggregateReplayer.replay(events, MeterState.Empty, MeterReducer, null, policy)
        val restored = AggregateReplayer.replay(events, MeterState.Empty, MeterReducer, snapshot, policy)

        log.debug { "restored: $restored" }
        restored shouldBeEqualTo full
        restored.streamVersion shouldBeEqualTo 2L
    }

    @Test
/**
 * 이 시나리오는 "invalid snapshot falls back to genesis replay" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `invalid snapshot falls back to genesis replay`() {
        val events = events()
        val invalid = AggregateSnapshotSeed<MeterState>(MeterState.Empty, 1, "wrong-hash", 1)
        val telemetry = RecordingReplayTelemetry()

        val restored = AggregateReplayer.replay(
            AggregateReplayRequest(events, MeterState.Empty, MeterReducer, invalid, policy),
            telemetry,
        )

        log.debug { "restored: $restored" }
        restored.streamVersion shouldBeEqualTo 2L
        restored.state.shouldBeInstanceOf<MeterState.Active>().meterCode shouldBeEqualTo "api_calls"

        log.debug { "telemetry: $telemetry" }
        telemetry.snapshotFallbacks shouldBeEqualTo listOf("invalid")
        telemetry.replays shouldBeEqualTo listOf("success" to 2)
    }

    @Test
/**
 * 이 시나리오는 "tampered persisted payload fails before decoding" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `tampered persisted payload fails before decoding`() {
        val events = events().toMutableList()
        events[1] = events[1].copy(payload = """{"unitPrice":999}""")

        assertFailsWith<EventHashMismatchException> {
            AggregateReplayer.replay(
                events = events,
                initialState = MeterState.Empty,
                reducer = MeterReducer,
                snapshot = null,
                policy = policy
            )
        }
    }

/**
 * events은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun events(): List<PersistedEvent> {
        val first = persisted(1, "meter.registered", """{"code":"api_calls"}""", null)
        val second = persisted(2, "price.activated", """{"unitPrice":0.10}""", first.eventHash)
        return listOf(first, second)
    }

/**
 * persisted은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun persisted(version: Long, type: String, payload: String, previousHash: String?): PersistedEvent {
        val material = EventHashMaterial(
            stream = stream,
            streamVersion = version,
            eventType = type,
            schemaVersion = 1,
            payload = payload,
            metadata = "{}",
            previousHash = previousHash
        )
        return PersistedEvent(
            eventId = UUID.randomUUID(),
            stream = stream,
            streamVersion = version,
            globalPosition = version,
            eventType = type,
            schemaVersion = 1,
            payload = payload,
            metadata = "{}",
            previousHash = previousHash,
            eventHash = CanonicalEventHash.sha256(material),
            occurredAt = Instant.EPOCH,
            recordedAt = Instant.EPOCH,
        )
    }

/**
 * RecordingReplayTelemetry 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class RecordingReplayTelemetry : ReplayTelemetry {
/**
 * replays은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val replays = mutableListOf<Pair<String, Int>>()
/**
 * snapshotFallbacks은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val snapshotFallbacks = mutableListOf<String>()

/**
 * recordReplay은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun recordReplay(outcome: String, eventCount: Int, duration: Duration) {
            check(!duration.isNegative)
            replays += outcome to eventCount
        }

/**
 * recordSnapshotFallback은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun recordSnapshotFallback(reason: String) {
            snapshotFallbacks += reason
        }
    }
}
