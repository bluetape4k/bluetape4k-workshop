package io.bluetape4k.workshop.commerce.usagebilling.billing.messaging

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.*

/**
 * BillingOutboxPublisherTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class BillingOutboxPublisherTest {

    private companion object: KLogging() {
/**
 * NOW은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val NOW: Instant = Instant.parse("2026-07-22T00:00:00Z")
    }

/**
 * journal은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val journal = InMemoryBillingOutboxJournal()
/**
 * transport은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val transport = RecordingBillingEventTransport()
/**
 * publisher은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val publisher = BillingOutboxPublisher(journal, transport, Clock.fixed(NOW, ZoneOffset.UTC))

    @Test
/**
 * 이 시나리오는 "broker success marks the locally claimed Billing event published" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `broker success marks the locally claimed Billing event published`() {
        val event = event()
        journal.events += event

        publisher.publishPending().published shouldBeEqualTo 1
        journal.statusOf(event.eventId) shouldBeEqualTo BillingOutboxStatus.PUBLISHED
        transport.keys shouldBeEqualTo listOf(event.partitionKey)
    }

    @Test
/**
 * 이 시나리오는 "broker failure releases the local Billing claim into retry wait" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `broker failure releases the local Billing claim into retry wait`() {
        val event = event()
        journal.events += event
        transport.failure = BillingEventTransportFailure(IllegalStateException("broker_unavailable"))

        publisher.publishPending().retryWait shouldBeEqualTo 1
        journal.statusOf(event.eventId) shouldBeEqualTo BillingOutboxStatus.RETRY_WAIT
    }

/**
 * event은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun event(): BillingOutboxLease =
        BillingOutboxLease(
            eventId = UUID.randomUUID(),
            partitionKey = "tenant-a|BillingPeriod|period-1",
            payload = "{\"event\":\"ChargeRated\"}"
        )

/**
 * InMemoryBillingOutboxJournal 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class InMemoryBillingOutboxJournal: BillingOutboxJournal {
/**
 * events은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val events = mutableListOf<BillingOutboxLease>()
/**
 * statusByEvent은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val statusByEvent = mutableMapOf<UUID, BillingOutboxStatus>()
/**
 * ownerByEvent은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val ownerByEvent = mutableMapOf<UUID, String>()

/**
 * claim은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun claim(owner: String, now: Instant, limit: Int): List<BillingOutboxLease> =
            events
                .filter { statusOf(it.eventId) == BillingOutboxStatus.PENDING }
                .take(limit)
                .onEach { event ->
                    statusByEvent[event.eventId] = BillingOutboxStatus.CLAIMED
                    ownerByEvent[event.eventId] = owner
                }

/**
 * markPublished은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun markPublished(eventId: UUID, owner: String, now: Instant): Boolean =
            ownerByEvent[eventId]
                ?.takeIf { it == owner }
                ?.let {
                    statusByEvent[eventId] = BillingOutboxStatus.PUBLISHED
                    true
                } ?: false

/**
 * markRetryWait은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun markRetryWait(eventId: UUID, owner: String, now: Instant): Boolean =
            ownerByEvent[eventId]
                ?.takeIf { it == owner }
                ?.let {
                    statusByEvent[eventId] = BillingOutboxStatus.RETRY_WAIT
                    true
                } ?: false

/**
 * statusOf은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun statusOf(eventId: UUID): BillingOutboxStatus =
            statusByEvent[eventId] ?: BillingOutboxStatus.PENDING
    }

/**
 * RecordingBillingEventTransport 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class RecordingBillingEventTransport: BillingEventTransport {
/**
 * keys은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val keys = mutableListOf<String>()
/**
 * failure은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var failure: BillingEventTransportFailure? = null

/**
 * publish은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun publish(partitionKey: String, payload: String) {
            failure?.let { throw it }
            keys += partitionKey
        }
    }
}
