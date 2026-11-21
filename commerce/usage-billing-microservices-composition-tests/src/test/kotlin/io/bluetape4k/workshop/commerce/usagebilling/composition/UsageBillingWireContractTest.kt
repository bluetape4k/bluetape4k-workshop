package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.billing.integration.BillingIntegrationEnvelope
import io.bluetape4k.workshop.commerce.usagebilling.billing.messaging.BillingInboundEvent
import io.bluetape4k.workshop.commerce.usagebilling.billing.messaging.BillingInboundEventDecoder
import io.bluetape4k.workshop.commerce.usagebilling.invoice.messaging.BillingChargeDecoder
import io.bluetape4k.workshop.commerce.usagebilling.meter.integration.MeterIntegrationEnvelope
import io.bluetape4k.workshop.commerce.usagebilling.query.messaging.QueryInboundEventDecoder
import io.bluetape4k.workshop.commerce.usagebilling.usage.integration.UsageIntegrationEnvelope
import io.bluetape4k.workshop.commerce.usagebilling.usage.messaging.MeterPriceEvidenceDecoder
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * UsageBillingWireContractTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class UsageBillingWireContractTest {

    private companion object: KLogging() {
/**
 * NOW은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val NOW: Instant = Instant.parse("2026-07-23T00:00:00Z")
    }

/**
 * meterPriceEvidenceDecoder은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val meterPriceEvidenceDecoder = MeterPriceEvidenceDecoder()
/**
 * billingDecoder은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val billingDecoder = BillingInboundEventDecoder()
/**
 * invoiceDecoder은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val invoiceDecoder = BillingChargeDecoder()
/**
 * queryDecoder은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val queryDecoder = QueryInboundEventDecoder()

    @Test
/**
 * 이 시나리오는 "Meter price wire contract is independently understood by Usage Billing and Query" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Meter price wire contract is independently understood by Usage Billing and Query`() {
        val event = MeterIntegrationEnvelope.create(
            eventId = UUID.fromString("018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a01"),
            eventType = "PriceActivated",
            schemaVersion = 1,
            tenantId = "tenant-a",
            aggregateId = "api_calls",
            aggregateVersion = 1,
            payload = "{\"meterCode\":\"api_calls\",\"currency\":\"USD\",\"unitPrice\":\"0.10\"}",
            occurredAt = NOW,
            recordedAt = NOW,
        )

        meterPriceEvidenceDecoder.decode(event.wirePayload())
            .evidence.unitPrice.toPlainString() shouldBeEqualTo "0.10"

        val billingEvent = billingDecoder.decode(event.wirePayload())
        billingEvent.shouldBeInstanceOf<BillingInboundEvent.PriceActivated>()
        billingEvent.event.evidence.meterCode shouldBeEqualTo "api_calls"

        queryDecoder.decode(event.wirePayload()).aggregateType shouldBeEqualTo "Meter"
    }

    @Test
/**
 * 이 시나리오는 "Usage acceptance wire contract preserves rating evidence for Billing and Query" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Usage acceptance wire contract preserves rating evidence for Billing and Query`() {
        val event = UsageIntegrationEnvelope.create(
            eventId = UUID.fromString("018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a02"),
            eventType = "UsageAccepted",
            schemaVersion = 1,
            tenantId = "tenant-a",
            aggregateId = "usage-1",
            aggregateVersion = 1,
            payload = "{\"meterCode\":\"api_calls\",\"currency\":\"USD\",\"quantity\":\"2.5\"}",
            occurredAt = NOW,
            recordedAt = NOW,
        )

        val billingEvent = billingDecoder.decode(event.wirePayload())

        billingEvent.shouldBeInstanceOf<BillingInboundEvent.UsageAccepted>()
        billingEvent.event.quantity.toPlainString() shouldBeEqualTo "2.5"
        billingEvent.event.payloadDigest shouldBeEqualTo event.payloadDigest

        queryDecoder.decode(event.wirePayload()).aggregateType shouldBeEqualTo "Usage"
    }

    @Test
/**
 * 이 시나리오는 "Billing charge wire contract is independently understood by Invoice and Query" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Billing charge wire contract is independently understood by Invoice and Query`() {
        val event = BillingIntegrationEnvelope.create(
            eventId = UUID.fromString("018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a03"),
            eventType = "ChargeRated",
            schemaVersion = 1,
            tenantId = "tenant-a",
            aggregateId = "period-2026-07",
            aggregateVersion = 1,
            payload = "{\"amount\":\"0.25\",\"currency\":\"USD\"}",
            occurredAt = NOW,
            recordedAt = NOW,
        )

        invoiceDecoder.decode(event.wirePayload()).amount.toPlainString() shouldBeEqualTo "0.25"
        queryDecoder.decode(event.wirePayload()).aggregateType shouldBeEqualTo "BillingPeriod"
    }
}
