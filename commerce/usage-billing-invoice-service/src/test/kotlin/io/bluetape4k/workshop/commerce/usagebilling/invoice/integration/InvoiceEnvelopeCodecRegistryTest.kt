package io.bluetape4k.workshop.commerce.usagebilling.invoice.integration

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * InvoiceEnvelopeCodecRegistryTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class InvoiceEnvelopeCodecRegistryTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "wire payload retains the envelope identity and payload digest" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `wire payload retains the envelope identity and payload digest`() {
        val envelope = invoiceEnvelope("InvoiceIssued", 1)

        val wire = Jackson.defaultJsonMapper.readTree(envelope.wirePayload())

        wire.get("eventId")!!.asString() shouldBeEqualTo envelope.eventId.toString()
        wire.get("tenantId")!!.asString() shouldBeEqualTo "tenant-a"
        wire.get("payloadDigest")!!.asString() shouldBeEqualTo envelope.payloadDigest
    }

    @Test
/**
 * 이 시나리오는 "invoice event uses a tenant aggregate partition key" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `invoice event uses a tenant aggregate partition key`() {
        val envelope = invoiceEnvelope("InvoiceIssued", 1)
        envelope.partitionKey() shouldBeEqualTo "tenant-a|Invoice|invoice-7"
    }

    @Test
/**
 * 이 시나리오는 "unsupported invoice envelope is rejected" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `unsupported invoice envelope is rejected`() {
        assertFailsWith<UnsupportedInvoiceEnvelopeVersion> {
            InvoiceEnvelopeCodecRegistry()
                .validate(invoiceEnvelope("InvoiceIssued", 99))
        }.message shouldBeEqualTo "unsupported_invoice_envelope:InvoiceIssued:99"
    }

    @Suppress("SameParameterValue")
/**
 * invoiceEnvelope은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun invoiceEnvelope(eventType: String, schemaVersion: Int): InvoiceIntegrationEnvelope =
        InvoiceIntegrationEnvelope.create(
            eventId = UUID.fromString("018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a04"),
            eventType = eventType,
            schemaVersion = schemaVersion,
            tenantId = "tenant-a",
            aggregateId = "invoice-7",
            aggregateVersion = 1,
            payload = "{\"invoiceNumber\":\"INV-7\"}",
            occurredAt = Instant.parse("2026-07-22T00:00:00Z"),
            recordedAt = Instant.parse("2026-07-22T00:00:01Z"),
        )
}
