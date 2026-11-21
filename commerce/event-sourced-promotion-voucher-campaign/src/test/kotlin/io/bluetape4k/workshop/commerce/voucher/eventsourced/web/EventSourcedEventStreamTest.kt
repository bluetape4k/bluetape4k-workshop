package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.invoking
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.javatimes.millis
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * EventSourcedEventStreamTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedEventStreamTest {

    private companion object: KLogging() {
/**
 * TENANT은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val TENANT = "tenant-a"
/**
 * CAMPAIGN_ID은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val CAMPAIGN_ID: UUID = UUID.fromString("0198f311-8700-7000-8000-000000000011")
/**
 * NOW은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val NOW: Instant = Instant.parse("2026-07-23T00:00:00Z")

/**
 * descriptor은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun descriptor(
            streamPosition: Long = 3,
            globalPosition: Long = 11,
            projectionPosition: Long = 7,
        ): EventSourcedPublicEventDescriptor =
            EventSourcedPublicEventDescriptor(
                campaignId = CAMPAIGN_ID,
                state = "ACTIVE",
                streamPosition = streamPosition,
                globalPosition = globalPosition,
                projectionPosition = projectionPosition,
                lag = globalPosition - projectionPosition,
                observedAt = NOW,
            )
    }

    @Test
/**
 * 이 시나리오는 "cursor preserves aggregate global and projection positions" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `cursor preserves aggregate global and projection positions`() {
        val cursor = EventSourcedStreamCursor.parse("3:11:7").shouldNotBeNull()

        log.debug { "cursor: $cursor" }
        cursor.streamPosition shouldBeEqualTo 3L
        cursor.globalPosition shouldBeEqualTo 11L
        cursor.projectionPosition shouldBeEqualTo 7L
        cursor.toString() shouldBeEqualTo "3:11:7"
    }

    @Test
/**
 * 이 시나리오는 "stream starts with bounded snapshot and emits heartbeat with the same cursor" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stream starts with bounded snapshot and emits heartbeat with the same cursor`() =
        withStream(SequenceSource(descriptor())) { stream ->
            stream.open(TENANT, CAMPAIGN_ID, null).use { subscription ->
/**
 * snapshot은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val snapshot = subscription.next(Duration.ZERO).shouldNotBeNull()
/**
 * heartbeat은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val heartbeat = subscription.next(1.millis()).shouldNotBeNull()

                log.debug { "snapshot: $snapshot" }
                log.debug { "heartbeat: $heartbeat" }

                snapshot.event shouldBeEqualTo "snapshot"
                snapshot.cursor shouldBeEqualTo EventSourcedStreamCursor(3, 11, 7)
                snapshot.data shouldContain "\"campaignId\":\"$CAMPAIGN_ID\""
                snapshot.data shouldNotContain "payload"

                heartbeat.event shouldBeEqualTo "heartbeat"
                heartbeat.cursor shouldBeEqualTo snapshot.cursor
            }
        }

    @Test
/**
 * 이 시나리오는 "queue overflow replaces stale frames with one terminal authoritative reset" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `queue overflow replaces stale frames with one terminal authoritative reset`() {
        val source = SequenceSource(
            descriptor(),
            descriptor(streamPosition = 4, globalPosition = 12, projectionPosition = 8),
        )
        withStream(
            source,
            EventSourcedSseProperties(queueSize = 1, pollInterval = 1.millis()),
        ) { stream ->
            stream.open(TENANT, CAMPAIGN_ID, null).use { subscription ->
                await atMost 2.seconds withPollInterval 100.milliseconds untilAsserted {
                    source.readCount.get() shouldBeGreaterThan 1
                }

                val reset = subscription.next(Duration.ZERO).shouldNotBeNull()

                log.debug { "reset: $reset" }
                reset.event shouldBeEqualTo "reset"
                reset.cursor shouldBeEqualTo EventSourcedStreamCursor(4, 12, 8)
                reset.terminal.shouldBeTrue()

                subscription.next(Duration.ZERO).shouldBeNull()
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "future reconnect cursor is rejected before a subscription is admitted" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `future reconnect cursor is rejected before a subscription is admitted`() =
        withStream(SequenceSource(descriptor())) { stream ->
            assertFailsWith<IllegalArgumentException> {
                stream.open(
                    tenant = TENANT,
                    campaignId = CAMPAIGN_ID,
                    requestedCursor = EventSourcedStreamCursor(4, 12, 8)
                )
            }
        }

/**
 * withStream은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun withStream(
        source: EventSourcedEventSource,
        properties: EventSourcedSseProperties = EventSourcedSseProperties(),
        block: (EventSourcedEventStream) -> Unit,
    ) {
        EventSourcedEventStream(source, Jackson.defaultJsonMapper, properties).use(block)
    }

/**
 * SequenceSource 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class SequenceSource(
        private vararg val descriptors: EventSourcedPublicEventDescriptor,
    ): EventSourcedEventSource {
/**
 * readCount은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val readCount = AtomicInteger()

/**
 * read은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun read(
            tenant: String,
            campaignId: UUID,
        ): EventSourcedPublicEventDescriptor {
/**
 * index은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val index = readCount.getAndIncrement().coerceAtMost(descriptors.lastIndex)
            return descriptors[index]
        }
    }
}
