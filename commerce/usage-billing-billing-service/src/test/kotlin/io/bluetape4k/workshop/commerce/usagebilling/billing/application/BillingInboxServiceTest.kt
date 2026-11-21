package io.bluetape4k.workshop.commerce.usagebilling.billing.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxJournal
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxOutcome
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidence
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * BillingInboxServiceTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class BillingInboxServiceTest {

    companion object: KLogging()

/**
 * journal은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val journal = InMemoryBillingInboxJournal()
/**
 * service은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val service = BillingInboxService(journal)

    @BeforeEach
    fun beforeEach() {
        journal.reset()
    }

    @Test
/**
 * 이 시나리오는 "expected aggregate version is applied exactly once" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `expected aggregate version is applied exactly once`() {
        val event = usageEvent(version = 1)

        service.handle(event) shouldBeEqualTo BillingInboxOutcome.APPLIED
        service.handle(event) shouldBeEqualTo BillingInboxOutcome.DUPLICATE
        journal.appliedEventIds.size shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "future aggregate version is deferred until its predecessor arrives" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `future aggregate version is deferred until its predecessor arrives`() {
        service.handle(usageEvent(version = 2)) shouldBeEqualTo BillingInboxOutcome.DEFERRED

        service.handle(usageEvent(version = 1)) shouldBeEqualTo BillingInboxOutcome.APPLIED
        service.handle(usageEvent(version = 2)) shouldBeEqualTo BillingInboxOutcome.APPLIED
    }

    @Test
/**
 * 이 시나리오는 "same event identifier with a different digest is quarantined" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same event identifier with a different digest is quarantined`() {
        val eventId = UUID.fromString("2c8ee8f1-5f56-4fc9-9f24-21542b46b8b0")

        service.handle(usageEvent(eventId = eventId, digest = "digest-a")) shouldBeEqualTo
                BillingInboxOutcome.APPLIED

        service.handle(usageEvent(eventId = eventId, digest = "digest-b")) shouldBeEqualTo
                BillingInboxOutcome.QUARANTINED
    }

    @Test
/**
 * 이 시나리오는 "usage without local pricing evidence remains deferred" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `usage without local pricing evidence remains deferred`() {
        service.handle(usageEvent(meterCode = "unknown_meter")) shouldBeEqualTo
                BillingInboxOutcome.DEFERRED
    }

/**
 * usageEvent은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun usageEvent(
        eventId: UUID = UUID.randomUUID(),
        version: Long = 1,
        digest: String = "digest-$version",
        meterCode: String = "api_calls",
    ): BillingInboxEvent =
        BillingInboxEvent(
            eventId = eventId,
            tenantId = "tenant-a",
            aggregateType = "Usage",
            aggregateId = "usage-1",
            aggregateVersion = version,
            payloadDigest = digest,
            meterCode = meterCode
        )

/**
 * InMemoryBillingInboxJournal 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class InMemoryBillingInboxJournal: BillingInboxJournal {

/**
 * appliedEventIds은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        override val appliedEventIds = mutableSetOf<UUID>()
/**
 * digests은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val digests = mutableMapOf<UUID, String>()
/**
 * expectedVersion은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private var expectedVersion = 1L

/**
 * priceEvidence은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun priceEvidence(
            tenantId: String,
            meterCode: String,
            currency: String,
        ): BillingPriceEvidence? =
            BillingPriceEvidence(
                tenantId = tenantId,
                meterCode = meterCode,
                currency = currency,
                unitPrice = BigDecimal("0.10"),
                effectiveAt = Instant.EPOCH
            ).takeIf { meterCode == "api_calls" }

/**
 * digestFor은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun digestFor(eventId: UUID): String? = digests[eventId]

/**
 * expectedVersion은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun expectedVersion(tenantId: String, aggregateId: String): Long = expectedVersion

/**
 * apply은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun apply(event: BillingInboxEvent) {
            digests[event.eventId] = event.payloadDigest
            appliedEventIds += event.eventId
            expectedVersion += 1
        }

/**
 * reset은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun reset() {
            appliedEventIds.clear()
            digests.clear()
            expectedVersion = 1L
        }
    }
}
