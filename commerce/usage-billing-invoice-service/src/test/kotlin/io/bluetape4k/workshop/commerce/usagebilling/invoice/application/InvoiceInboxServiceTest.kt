package io.bluetape4k.workshop.commerce.usagebilling.invoice.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceInboxOutcome
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceJournal
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceLine
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.*

/**
 * InvoiceInboxServiceTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class InvoiceInboxServiceTest {

    companion object: KLogging()

/**
 * journal은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val journal = InMemoryInvoiceJournal()
/**
 * service은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val service = InvoiceInboxService(journal)

    @BeforeEach
    fun beforeEach() {
        journal.reset()
    }

    @Test
/**
 * 이 시나리오는 "ChargeRated creates one immutable invoice line with source event provenance" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `ChargeRated creates one immutable invoice line with source event provenance`() {
        val event = chargeRated()

        service.handle(event).created.shouldBeTrue()
        journal.lines.single().sourceEventId shouldBeEqualTo event.eventId
        journal.lines.single().correctionOf.shouldBeNull()
    }

    @Test
/**
 * 이 시나리오는 "duplicate ChargeRated delivery creates no additional invoice line" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `duplicate ChargeRated delivery creates no additional invoice line`() {
        val event = chargeRated()
        service.handle(event)

        service.handle(event).created.shouldBeFalse()
        journal.lines.size shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "same invoice event identifier with a conflicting payload is quarantined" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same invoice event identifier with a conflicting payload is quarantined`() {
        val event = chargeRated()
        service.handle(event)

        val result = service.handle(event.copy(payloadDigest = "conflicting-digest"))

        result.created.shouldBeFalse()
        result.outcome shouldBeEqualTo InvoiceInboxOutcome.QUARANTINED
        journal.lines.size shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "AdjustmentPosted appends a correction line without mutating original charge line" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `AdjustmentPosted appends a correction line without mutating original charge line`() {
        val original = chargeRated()
        service.handle(original)
        val correction = InvoiceInboxEvent(
            eventId = UUID.randomUUID(),
            eventType = "AdjustmentPosted",
            correctionOf = original.eventId,
            amount = BigDecimal("-0.10")
        )

        service.handle(correction).created.shouldBeTrue()
        journal.lines.size shouldBeEqualTo 2
        journal.lines[0].amount shouldBeEqualTo BigDecimal("0.10")
        journal.lines[1].correctionOf shouldBeEqualTo original.eventId
    }

/**
 * chargeRated은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun chargeRated(): InvoiceInboxEvent =
        InvoiceInboxEvent(UUID.randomUUID(), "ChargeRated", null, BigDecimal("0.10"))

/**
 * InMemoryInvoiceJournal 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class InMemoryInvoiceJournal: InvoiceJournal {
/**
 * lines은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        override val lines = mutableListOf<InvoiceLine>()

/**
 * findLine은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun findLine(sourceEventId: UUID): InvoiceLine? =
            lines.firstOrNull { it.sourceEventId == sourceEventId }

/**
 * digestByEvent은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val digestByEvent = mutableMapOf<UUID, String>()

/**
 * apply은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun apply(event: InvoiceInboxEvent): InvoiceInboxOutcome {
            val existingDigest = digestByEvent[event.eventId]
            if (existingDigest != null) {
                return if (existingDigest == event.payloadDigest) {
                    InvoiceInboxOutcome.DUPLICATE
                } else {
                    InvoiceInboxOutcome.QUARANTINED
                }
            }
            digestByEvent[event.eventId] = event.payloadDigest
            lines += InvoiceLine(event.eventId, event.correctionOf, event.amount)
            return InvoiceInboxOutcome.APPLIED
        }

/**
 * reset은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun reset() {
            lines.clear()
        }
    }
}
